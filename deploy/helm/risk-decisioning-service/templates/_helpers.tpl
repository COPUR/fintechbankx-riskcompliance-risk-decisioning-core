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

{{- define "risk.oidcClientSecretName" -}}
{{ include "risk.name" . }}-oidc-client
{{- end -}}

{{/*
The Secrets Manager entry of the identity client credential: exactly
<env>/<service account>/oidc-client (identity CLIENT_SECRET_DELIVERY.md), the one
the realm import reads, so the value the service sends is the value Keycloak holds.
Required; risk.guard runs this before any workload renders.
*/}}
{{- define "risk.oidcClientEntry" -}}
{{- $entry := trim (toString (default "" .Values.externalSecret.oidcClientSecretName)) -}}
{{- if not $entry -}}
{{- fail (printf "externalSecret.oidcClientSecretName is required: the Secrets Manager entry <env>/%s/oidc-client holding the identity client credential (client_secret) and token endpoint (token_uri), synced by the chart's oidc-client ExternalSecret" .Values.serviceAccount.name) -}}
{{- end -}}
{{- if not (regexMatch (printf "^[a-z0-9-]+/%s/oidc-client$" (regexQuoteMeta (toString .Values.serviceAccount.name))) $entry) -}}
{{- fail (printf "externalSecret.oidcClientSecretName must be <env>/%s/oidc-client (the one home of the identity client credential), got %s" .Values.serviceAccount.name $entry) -}}
{{- end -}}
{{- $entry -}}
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

{{/* The migration Job reads the schema owner's credential; refuse a render without it, equal to the runtime secret, or outside <env>/<service account>/. */}}
{{- define "risk.requireMigrationSecret" -}}
{{- if not .Values.externalSecret.enabled -}}
{{- fail "externalSecret.enabled must be true: the migration Job reads the schema owner's credential (externalSecret.migrationSecretName)" -}}
{{- end -}}
{{- $key := required "externalSecret.migrationSecretName is required: the migration Job runs Flyway as the schema owner (Secrets Manager <env>/<service account>/db-migration)" .Values.externalSecret.migrationSecretName -}}
{{- /* The single-user fallback: one credential for the Job and the service would run Flyway and the
       service as the same role (decision 0002). Names are compared trimmed and without a trailing "/". */ -}}
{{- $migration := trimSuffix "/" (trim (toString $key)) -}}
{{- $runtime := trimSuffix "/" (trim (toString (default "" .Values.externalSecret.remoteSecretName))) -}}
{{- if eq $migration $runtime -}}
{{- fail "externalSecret.migrationSecretName must not be the runtime secret (externalSecret.remoteSecretName): the migration Job runs Flyway as the schema owner and the service as the runtime role, never one credential for both" -}}
{{- end -}}
{{- /* Literal prefix (regexQuoteMeta, as in customer); risk.guard has already refused any name that is not a DNS label. */ -}}
{{- if not (regexMatch (printf "^[a-z0-9-]+/%s/" (regexQuoteMeta (toString .Values.serviceAccount.name))) $key) -}}
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
Datasource / TLS guard. fbx.guard is _fbx_helpers.tpl, a whole, header-less
copy of cicd-templates charts/fintechbankx-service/templates/_helpers.tpl at
6b6c317 (README "Vendoring the guard"; CI step "Vendored platform guard" runs
scripts/ci/verify-vendored-guard.sh on it); risk.guard is this chart's adapter
and its own checks, and no define of this chart is named fbx.*.
deployment.yaml and migration-job.yaml both start with
{{- include "risk.guard" . -}}, so neither workload renders from values the
other would refuse.

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
                   PostgreSQL database); mountPath, key and configMapName from
                   .Values.databaseCa, the values risk.databaseCaVolume and
                   risk.databaseCaMount write. The guard pins them to
                   /etc/fintechbankx/rds-ca, global-bundle.pem and rds-ca-bundle
                   (a null configMapName is refused as missing) and requires
                   DB_URL's sslrootcert=<mountPath>/<key>
  kafka.runtime    .Values.kafka.runtime (msk or strimzi)
  externalSecret   enabled from .Values.externalSecret.enabled; data is the
                   runtime secret this chart renders (SPRING_DATASOURCE_PASSWORD
                   <- remoteSecretName "password") and the identity client
                   secret (CUSTOMER_CLIENT_CREDENTIAL <- oidcClientSecretName
                   "client_secret", CUSTOMER_TOKEN_URI <- "token_uri"),
                   extraData the migration secret (DB_MIGRATION_USERNAME /
                   _PASSWORD <- migrationSecretName), dataFrom
                   .Values.externalSecret.dataFrom (refused when set)

The guard refuses, in every relaxed-binding spelling: datasource, Flyway,
Liquibase and R2DBC names (but not SPRING_DATASOURCE_USERNAME/_PASSWORD),
spring.application.json, JDBC URL and TLS parameter names (DB_SSL_ROOT_CERT
included: the chart renders it from databaseCa, and DatabaseTlsGuard, on by
default like KafkaTlsGuard, requires it), DB_URL in another spelling, spring.config.*,
spring.profiles.* (the chart renders the only profile, from kafka.runtime),
spring.ssl.* and SSL bundles, fintechbankx.tls.*, Kafka client TLS names
(spring.kafka[.<client>][.properties].ssl.*, except the value-checked
endpoint identification algorithm), spring.data.mongodb.* and MONGODB_URI,
KAFKA_TLS_* (only from a Secret: this chart renders them with secretKeyRef
from kafkaTls.secretName), each name also in its property reading ('_' inside
an element), a JVM options value that names a datasource, TLS, config, Kafka
or MongoDB property, a Kafka security.protocol other
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
the wire protocol, with row data, to the pod log); FINTECHBANKXTLS* after
normalisation (the guard refuses fintechbankx.tls.* only with a separator
after fintechbankx and tls; Spring Boot 3.3.6 binds fintechbankx.tls.enforce,
the test switch of the TLS startup assertions, only from
FINTECHBANKX_TLS_ENFORCE, so cicd-templates does not count
FINTECHBANKXTLSENFORCE as a gap: defence in depth); SPRINGMAIN* after
normalisation (spring.main.* changes how the application context starts:
lazy-initialization can skip guards that run when a bean is created,
allow-bean-definition-overriding can replace one, sources can add
configuration; the startup guards are BeanFactoryPostProcessors, which lazy
initialisation does not defer, and the guard does not cover the family:
defence in depth); SPRINGJPA*, SPRINGSQLINIT*, HIBERNATE* and
JAKARTA/JAVAXPERSISTENCE* after normalisation (Hibernate schema management and
SQL init would change the schema at startup, before the runtime-role check;
the schema belongs to the migration Job);
a DB_URL other than plain
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
      "databaseCa" (dict "enabled" true "mountPath" .Values.databaseCa.mountPath "key" .Values.databaseCa.key
        "configMapName" .Values.databaseCa.configMapName)
      "kafka" (dict "runtime" ((.Values.kafka | default dict).runtime | default ""))
      "externalSecret" (dict "enabled" $es.enabled
        "data" (list
          (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" $es.remoteSecretName)
          (dict "secretKey" "CUSTOMER_CLIENT_CREDENTIAL" "property" "client_secret" "remoteSecretName" $es.oidcClientSecretName)
          (dict "secretKey" "CUSTOMER_TOKEN_URI" "property" "token_uri" "remoteSecretName" $es.oidcClientSecretName))
        "extraData" (list
          (dict "secretKey" "DB_MIGRATION_USERNAME" "property" "username" "remoteSecretName" $es.migrationSecretName)
          (dict "secretKey" "DB_MIGRATION_PASSWORD" "property" "password" "remoteSecretName" $es.migrationSecretName))
        "dataFrom" $es.dataFrom))) -}}
{{- range $route := list "extraEnv" "env" "javaToolOptions" -}}
{{- if index $.Values $route -}}
{{- fail (printf "%s is not a value of this chart: it renders no such env route (config keys go through the datasource/TLS guard; the image fixes JVM options)" $route) -}}
{{- end -}}
{{- end -}}
{{- if hasKey .Values "customerClient" -}}
{{- fail "customerClient is not a value of this chart: the identity client credential comes from the chart's own oidc-client ExternalSecret (externalSecret.oidcClientSecretName), never from a Secret created outside the chart" -}}
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
{{- if hasPrefix "FINTECHBANKXTLS" $name -}}
{{- fail (printf "config.%s must not be set: fintechbankx.tls.enforce is the test switch of the TLS startup assertions, which are on by default; only the local profile and tests turn them off, never a cluster" $key) -}}
{{- end -}}
{{- if hasPrefix "SPRINGMAIN" $name -}}
{{- fail (printf "config.%s must not be set: spring.main.* changes how the application context starts (lazy initialisation, bean definition overriding, sources); it is not an install-time value" $key) -}}
{{- end -}}
{{- if regexMatch "^(SPRINGJPA|SPRINGSQLINIT|HIBERNATE|JAKARTAPERSISTENCE|JAVAXPERSISTENCE|RISKDATABASEFLYWAY)" $name -}}
{{- fail (printf "config.%s must not be set: the schema belongs to the migration Job; Hibernate schema management, SQL init and risk.database.flyway would change it at startup, before the runtime-role check" $key) -}}
{{- end -}}
{{- /* The identity client credential and token endpoint (risk.customer.client-credential / token-uri, bound from
       CUSTOMER_CLIENT_CREDENTIAL / CUSTOMER_TOKEN_URI) in any relaxed-binding spelling: Secret only. */ -}}
{{- if regexMatch "^(RISK)?CUSTOMER(CLIENTCREDENTIAL|TOKENURI)$" $name -}}
{{- fail (printf "config.%s must not be set: the identity client credential and token endpoint come only from the oidc-client ExternalSecret (externalSecret.oidcClientSecretName; CUSTOMER_CLIENT_CREDENTIAL and CUSTOMER_TOKEN_URI by secretKeyRef on the app pods), never the ConfigMap" $key) -}}
{{- end -}}
{{- end -}}
{{- /* Screening facts and the customer client (screening-facts plan): values the pods must not start with half-set. */ -}}
{{- $home := trim (toString (default "" (index .Values.config "RISK_HOME_COUNTRY"))) -}}
{{- if and $home (not (regexMatch "^[A-Z]{2}$" $home)) -}}
{{- fail (printf "config.RISK_HOME_COUNTRY must be an upper-case ISO 3166-1 alpha-2 code (the country of the bank's own ACC- accounts), got %q" $home) -}}
{{- end -}}
{{- $customer := trim (toString (default "" (index .Values.config "CUSTOMER_SERVICE_URL"))) -}}
{{- if and $customer (not (regexMatch "^https?://[^/[:space:]]+" $customer)) -}}
{{- fail (printf "config.CUSTOMER_SERVICE_URL must be an http(s) URL of the customer service, got %q" $customer) -}}
{{- end -}}
{{- if or (contains "${" $customer) (contains "$(" $customer) -}}
{{- fail "config.CUSTOMER_SERVICE_URL must not contain '${' or '$(': it would be resolved after the check" -}}
{{- end -}}
{{- $ownClient := trim (toString (default "svc-rsk-decisioning" (index .Values.config "CUSTOMER_CLIENT_ID"))) -}}
{{- range $caller := splitList "," (toString (default "" (index .Values.config "SERVICE_CALLERS_ELIGIBILITY"))) -}}
{{- if eq (trim $caller) $ownClient -}}
{{- fail (printf "config.SERVICE_CALLERS_ELIGIBILITY must not list this service's own client (%s): a token this service obtains for the customer read must never also open its own loan-eligibility endpoint" $ownClient) -}}
{{- end -}}
{{- end -}}
{{- $_ := include "risk.oidcClientEntry" . -}}
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
