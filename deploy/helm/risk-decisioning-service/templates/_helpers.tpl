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


{{/*
Aurora TLS (cicd-templates 4f0f266): the database connection must verify the
server certificate and host name against the mounted RDS CA bundle;
sslmode=require encrypts but trusts any certificate.

The query after the first "?" is read the way PgJDBC reads it (split on "&",
name before the first "="). PgJDBC lets the last of repeated parameters win,
so sslmode and sslrootcert must each appear exactly once; a custom sslfactory,
sslhostnameverifier or sslpasswordcallback would replace the verification.
Values are compared undecoded (PgJDBC decodes values, not names), so an encoded
value fails closed. The
ConfigMap exports every config key, so a second URL there (SPRING_DATASOURCE_*URL,
SPRING_FLYWAY_URL, SPRING_APPLICATION_JSON) would override DB_URL and is refused
too, and so is a config location or import (SPRING_CONFIG_IMPORT,
SPRING_CONFIG_ADDITIONAL_LOCATION, SPRING_CONFIG_LOCATION, also indexed), which
loads a file or configtree that can set the URL. JVM option variables
(JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS, JAVA_OPTS) are refused
because they set system properties or load an agent before application.yml is
read; the image fixes them. LOGGING_LEVEL_* is refused because a log level is
not an install-time value: LOGGING_LEVEL_ORG_POSTGRESQL=TRACE would write the
wire protocol, with row data, to the pod log. Each key is normalised before
matching, the way Spring's relaxed binding reads it from the environment: upper
case, then every non-alphanumeric character dropped, so dash, dot, underscore
and index spellings (spring.config.import[0], SPRING_CONFIG_IMPORT_0) all
match. The chart renders no Spring config import of its own; a configtree would
be allowed only as a chart-rendered value on the fixed mount
optional:configtree:/etc/fintechbankx/config/, never from a values key. The
application's DatabaseTlsGuard repeats the URL checks at startup.
*/}}
{{- define "risk.validateDatabaseTls" -}}
{{- $bundle := "/etc/fintechbankx/rds-ca/global-bundle.pem" -}}
{{- range $key, $_ := .Values.config -}}
{{- $name := upper (regexReplaceAll "[^A-Za-z0-9]" (toString $key) "") -}}
{{- if regexMatch "^SPRING(DATASOURCE.*URL|DATASOURCEHIKARIDATASOURCEPROPERTIES.*|FLYWAYURL|APPLICATIONJSON)$" $name -}}
{{- fail (printf "config.%s must not be set: config.DB_URL is the only database URL (sslmode=verify-full)" $key) -}}
{{- end -}}
{{- if regexMatch "^SPRINGCONFIG(IMPORT|ADDITIONALLOCATION|LOCATION)[0-9]*$" $name -}}
{{- fail (printf "config.%s must not be set: it loads configuration that can override config.DB_URL" $key) -}}
{{- end -}}
{{- if regexMatch "^(JAVATOOLOPTIONS|JDKJAVAOPTIONS|JAVAOPTIONS|JAVAOPTS)$" $name -}}
{{- fail (printf "config.%s must not be set: JVM options are fixed by the image" $key) -}}
{{- end -}}
{{- if hasPrefix "LOGGINGLEVEL" $name -}}
{{- fail (printf "config.%s must not be set: log levels are not install-time values" $key) -}}
{{- end -}}
{{- end -}}
{{- $url := toString (default "" (index .Values.config "DB_URL")) -}}
{{- if $url -}}
{{- if not (hasPrefix "jdbc:postgresql:" $url) -}}
{{- fail (printf "config.DB_URL must be a jdbc:postgresql URL with sslmode=verify-full (with sslrootcert=%s)" $bundle) -}}
{{- end -}}
{{- $query := "" -}}
{{- if contains "?" $url -}}
{{- $query = (splitn "?" 2 $url)._1 -}}
{{- end -}}
{{- $sslmode := list -}}
{{- $rootcert := list -}}
{{- range $param := splitList "&" $query -}}
{{- $kv := splitn "=" 2 $param -}}
{{- $k := $kv._0 -}}
{{- $v := toString (default "" $kv._1) -}}
{{- if eq $k "sslmode" -}}
{{- $sslmode = append $sslmode $v -}}
{{- else if eq $k "sslrootcert" -}}
{{- $rootcert = append $rootcert $v -}}
{{- else if has $k (list "sslfactory" "sslhostnameverifier" "sslpasswordcallback") -}}
{{- fail (printf "config.DB_URL must not set %s: it replaces certificate or host name verification" $k) -}}
{{- end -}}
{{- end -}}
{{- if ne (toJson $sslmode) (toJson (list "verify-full")) -}}
{{- fail (printf "config.DB_URL must use sslmode=verify-full, exactly once (with sslrootcert=%s)" $bundle) -}}
{{- end -}}
{{- if ne (toJson $rootcert) (toJson (list $bundle)) -}}
{{- fail (printf "config.DB_URL must set sslrootcert=%s, exactly once" $bundle) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "risk.databaseCaFile" -}}
{{- printf "%s/%s" (trimSuffix "/" .Values.databaseCa.mountPath) .Values.databaseCa.key -}}
{{- end -}}
