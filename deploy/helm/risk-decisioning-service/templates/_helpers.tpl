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
A PostgreSQL DB_URL must verify the server certificate and host name:
sslmode=require encrypts but trusts any certificate (cicd-templates 4f0f266).
*/}}
{{- define "risk.validateDatabaseTls" -}}
{{- $url := toString (default "" (index .Values.config "DB_URL")) -}}
{{- if and (hasPrefix "jdbc:postgresql:" $url) (not (contains "sslmode=verify-full" $url)) -}}
{{- fail "config.DB_URL must use sslmode=verify-full (with sslrootcert=<databaseCa.mountPath>/<databaseCa.key>)" -}}
{{- end -}}
{{- end -}}
