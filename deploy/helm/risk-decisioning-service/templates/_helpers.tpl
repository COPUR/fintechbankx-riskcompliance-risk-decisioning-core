{{- define "risk.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "risk.selectorLabels" -}}
app.kubernetes.io/name: {{ include "risk.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "risk.labels" -}}
{{ include "risk.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "risk.secretName" -}}
{{ include "risk.name" . }}-db
{{- end -}}

{{- define "risk.migrationSecretName" -}}
{{ include "risk.name" . }}-db-migration
{{- end -}}

{{/*
Flyway migration Job (templates/migration-job.yaml, decision 0002). Its pods
carry app.kubernetes.io/name=<service account>, on which the mesh grants Aurora
egress, and app.kubernetes.io/component=db-migration (cicd-templates 335a345);
every selector of the app pods includes component=service, so none selects
them. The Job's ServiceAccount, Job and the db-migration ExternalSecret share
the name <service account>-db-migration.
*/}}
{{- define "risk.migrationName" -}}
{{ .Values.serviceAccount.name }}-db-migration
{{- end -}}

{{- define "risk.migrationSelectorLabels" -}}
app.kubernetes.io/name: {{ .Values.serviceAccount.name | quote }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: db-migration
{{- end -}}

{{- define "risk.migrationLabels" -}}
{{ include "risk.migrationSelectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/*
Hook resources the Job needs on a first install, when no regular resource of
the release exists yet: created before the Job (lower weight) and deleted once
every hook has succeeded, so the schema owner's credential exists in the
namespace only while a migration runs. A failed run leaves them for
inspection; the next install or upgrade replaces them.
*/}}
{{- define "risk.migrationPrerequisiteHook" -}}
helm.sh/hook: pre-install,pre-upgrade
helm.sh/hook-weight: "-10"
helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
{{- end -}}

{{/* The migration Job reads the schema owner's credential; refuse a render without it, or outside <env>/<service account>/. */}}
{{- define "risk.requireMigrationSecret" -}}
{{- if not .Values.externalSecret.enabled -}}
{{- fail "externalSecret.enabled must be true: the migration Job reads the schema owner's credential (externalSecret.migrationSecretName)" -}}
{{- end -}}
{{- $key := required "externalSecret.migrationSecretName is required: the migration Job runs Flyway as the schema owner (Secrets Manager <env>/<service account>/db-migration)" .Values.externalSecret.migrationSecretName -}}
{{- if not (regexMatch (printf "^[a-z0-9-]+/%s/" .Values.serviceAccount.name) $key) -}}
{{- fail (printf "externalSecret.migrationSecretName must be <env>/%s/..., got %s" .Values.serviceAccount.name $key) -}}
{{- end -}}
{{- end -}}

{{/* Shared by the app pods and the migration Job pods. */}}
{{- define "risk.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "risk.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{- define "risk.image" -}}
{{ required "image.repository is required" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}
{{- end -}}

{{/* RDS CA bundle volume. Not optional: without the bundle the pod must not start. */}}
{{- define "risk.databaseCaVolume" -}}
- name: database-ca
  configMap:
    name: {{ .Values.databaseCa.configMapName | quote }}
    items:
      - key: {{ .Values.databaseCa.key | quote }}
        path: {{ .Values.databaseCa.key | quote }}
{{- end -}}

{{- define "risk.databaseCaMount" -}}
- name: database-ca
  mountPath: {{ .Values.databaseCa.mountPath | quote }}
  readOnly: true
{{- end -}}


{{/*
Datasource / TLS guard. fbx.guard is vendored unchanged from cicd-templates
a4f0072 (_fbx_helpers.tpl, README "Vendoring the guard"); risk.guard is this
chart's adapter and its own checks. deployment.yaml and migration-job.yaml
both start with {{- include "risk.guard" . -}}, so neither workload renders
from values the other would refuse.

Adapter: every route of this chart that feeds the pod's environment.
  config           .Values.config (the ConfigMap the app loads with envFrom;
                   the migration Job's DB_URL and DB_USERNAME come from it)
  extraEnv         none: the chart renders only its own env entries
                   (DB_SSL_ROOT_CERT, SPRING_PROFILES_ACTIVE, KAFKA_TLS_*,
                   and the Job's DB_URL / DB_USERNAME from config)
  envFrom, extraEnvFrom
                   .Values.envFrom, .Values.extraEnvFrom: not values of this
                   chart, so the guard refuses them when set
  javaToolOptions  none: the image fixes JVM options (a value is refused below)
  databaseCa       enabled always (no switch: this service always has a
                   PostgreSQL database), mountPath and key from .Values.databaseCa;
                   the guard requires DB_URL's sslrootcert=<mountPath>/<key>
  kafka.runtime    .Values.kafka.runtime (msk or strimzi)
  externalSecret   enabled from .Values.externalSecret.enabled; data is the
                   runtime secret this chart renders (SPRING_DATASOURCE_PASSWORD
                   <- remoteSecretName "password"), extraData the migration
                   secret (DB_MIGRATION_USERNAME / _PASSWORD <- migrationSecretName),
                   dataFrom .Values.externalSecret.dataFrom (refused when set)

The guard refuses, in every relaxed-binding spelling: datasource, Flyway,
Liquibase and R2DBC names (but not SPRING_DATASOURCE_USERNAME/_PASSWORD),
spring.application.json, JDBC URL and TLS parameter names (DB_SSL_ROOT_CERT
included: the chart renders it from databaseCa, and it arms DatabaseTlsGuard
and KafkaTlsGuard), DB_URL in another spelling, spring.config.*,
spring.profiles.* (the chart renders the only profile, from kafka.runtime),
spring.ssl.* and SSL bundles, fintechbankx.tls.*, a JVM options value that
names a datasource, TLS or config property, a Kafka security.protocol other
than SASL_SSL (msk) or SSL (strimzi) and an endpoint identification
algorithm other than https; it parses every jdbc:postgresql: value the way
PgJDBC does (exactly one sslmode=verify-full and one sslrootcert=<bundle>, no
sslfactory, sslfactoryarg, sslhostnameverifier, sslpasswordcallback or
service, lower-case TLS names, no '${' or '$('), and refuses config keys and
secret keys that are not ConfigMap / Secret keys and secret names with a
control character.

This chart adds what the guard allows but this service must not take from
values: SPRING_DATASOURCE_USERNAME/_PASSWORD in config (the runtime role is
config.DB_USERNAME, its password only in the ExternalSecret); any JVM option
name in config, whatever the value (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS,
_JAVA_OPTIONS, JAVA_OPTS: the image fixes them); LOGGING_LEVEL_* (a log level
is not an install-time value: LOGGING_LEVEL_ORG_POSTGRESQL=TRACE would write
the wire protocol, with row data, to the pod log); a DB_URL other than plain
jdbc:postgresql: (the service connects with PgJDBC, no wrapper);
extraEnv, env and javaToolOptions values (the chart renders none); a
kafka.runtime other than msk or strimzi; strimzi without kafkaTls.secretName;
and a serviceAccount.name that is not a DNS label (it is written into labels,
names and the secret path rules). Config keys are normalised for these
checks the way relaxed binding reads them from the environment: upper case,
every non-alphanumeric character dropped.
*/}}
{{- define "risk.guard" -}}
{{- $es := .Values.externalSecret -}}
{{- include "fbx.guard" (dict "Values" (dict
      "config" .Values.config
      "extraEnv" (list)
      "envFrom" .Values.envFrom
      "extraEnvFrom" .Values.extraEnvFrom
      "javaToolOptions" ""
      "databaseCa" (dict "enabled" true "mountPath" .Values.databaseCa.mountPath "key" .Values.databaseCa.key)
      "kafka" (dict "runtime" ((.Values.kafka | default dict).runtime | default ""))
      "externalSecret" (dict "enabled" $es.enabled
        "data" (list (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" $es.remoteSecretName))
        "extraData" (list
          (dict "secretKey" "DB_MIGRATION_USERNAME" "property" "username" "remoteSecretName" $es.migrationSecretName)
          (dict "secretKey" "DB_MIGRATION_PASSWORD" "property" "password" "remoteSecretName" $es.migrationSecretName))
        "dataFrom" $es.dataFrom))) -}}
{{- range $route := list "extraEnv" "env" "javaToolOptions" -}}
{{- if index $.Values $route -}}
{{- fail (printf "%s is not a value of this chart: it renders no such env route (config keys go through the datasource/TLS guard; the image fixes JVM options)" $route) -}}
{{- end -}}
{{- end -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$" (toString .Values.serviceAccount.name)) -}}
{{- fail (printf "serviceAccount.name must be a DNS label ([a-z0-9-], at most 63 characters), got %q" (toString .Values.serviceAccount.name)) -}}
{{- end -}}
{{- range $key, $value := .Values.config -}}
{{- $name := upper (regexReplaceAll "[^A-Za-z0-9]" (toString $key) "") -}}
{{- if regexMatch "^SPRINGDATASOURCE(USERNAME|PASSWORD)$" $name -}}
{{- fail (printf "config.%s must not be set: the runtime role is config.DB_USERNAME and its password comes only from the ExternalSecret" $key) -}}
{{- end -}}
{{- if regexMatch "^(JAVATOOLOPTIONS|JDKJAVAOPTIONS|JAVAOPTIONS|JAVAOPTS)$" $name -}}
{{- fail (printf "config.%s must not be set: JVM options are fixed by the image" $key) -}}
{{- end -}}
{{- if hasPrefix "LOGGINGLEVEL" $name -}}
{{- fail (printf "config.%s must not be set: log levels are not install-time values" $key) -}}
{{- end -}}
{{- end -}}
{{- $url := trim (toString (default "" (index .Values.config "DB_URL"))) -}}
{{- if and $url (not (hasPrefix "jdbc:postgresql:" $url)) -}}
{{- fail "config.DB_URL must be a jdbc:postgresql: URL: the service connects with PgJDBC, without a wrapper driver" -}}
{{- end -}}
{{- if not (include "fbx.kafkaProfile" .) -}}
{{- fail "kafka.runtime must be msk or strimzi: this service always configures a Kafka producer (the outbox relay)" -}}
{{- end -}}
{{- if and (eq (toString .Values.kafka.runtime) "strimzi") (not .Values.kafkaTls.secretName) -}}
{{- fail "kafkaTls.secretName is required for kafka.runtime strimzi: the kafka-strimzi profile reads the client certificate from KAFKA_TLS_CERT, KAFKA_TLS_KEY and KAFKA_TLS_CA" -}}
{{- end -}}
{{- end -}}

{{/*
An env[].value the chart writes. Kubernetes expands $(VAR) in env values from
earlier entries and envFrom keys after the render, so the pod would get text
nobody checked (the migration Job's DB_USERNAME could become the owner's
name); refuse it, then quote the value. Called as
include "risk.envValue" (list "<NAME>" <value>).
*/}}
{{- define "risk.envValue" -}}
{{- $value := toString (index . 1) -}}
{{- if contains "$(" $value -}}
{{- fail (printf "%s must not contain '$(': Kubernetes would expand it from other env entries after the render" (index . 0)) -}}
{{- end -}}
{{- $value | quote -}}
{{- end -}}

{{- define "risk.databaseCaFile" -}}
{{- printf "%s/%s" (trimSuffix "/" .Values.databaseCa.mountPath) .Values.databaseCa.key -}}
{{- end -}}
