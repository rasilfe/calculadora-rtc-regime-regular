#!/usr/bin/env sh
# Gera os recursos RabbitMQ de uma empresa a partir do CNPJ (numérico ou alfanumérico).
#   ./k8s/empresas/gerar-empresa.sh 12.ABC.345/01DE-35 | kubectl apply -f -
# Para remover a empresa:
#   kubectl -n rtc delete user,permission,topicpermission,queue,binding -l rtc.serpro.gov.br/cnpj=<cnpj-minusculo>
set -eu

if [ "$#" -ne 1 ]; then
  echo "uso: $0 <CNPJ>" >&2
  exit 2
fi

CNPJ=$(printf '%s' "$1" | tr -d './ -' | tr '[:lower:]' '[:upper:]')
if ! printf '%s' "$CNPJ" | grep -Eq '^[0-9A-Z]{12}[0-9]{2}$'; then
  echo "CNPJ em formato inválido: $1" >&2
  exit 1
fi

# Dígitos verificadores (mesma regra do worker: valor do caractere = ASCII - 48).
dv() {
  base=$1; pesos=$2; soma=0; i=1
  for p in $pesos; do
    c=$(printf '%s' "$base" | cut -c"$i")
    v=$(( $(printf '%d' "'$c") - 48 ))
    soma=$(( soma + v * p )); i=$(( i + 1 ))
  done
  r=$(( soma % 11 ))
  if [ "$r" -lt 2 ]; then echo 0; else echo $(( 11 - r )); fi
}
D1=$(dv "$(printf '%s' "$CNPJ" | cut -c1-12)" "5 4 3 2 9 8 7 6 5 4 3 2")
D2=$(dv "$(printf '%s' "$CNPJ" | cut -c1-13)" "6 5 4 3 2 9 8 7 6 5 4 3 2")
if [ "$(printf '%s' "$CNPJ" | cut -c13-14)" != "$D1$D2" ]; then
  echo "CNPJ com dígitos verificadores inválidos: $1" >&2
  exit 1
fi

cnpj=$(printf '%s' "$CNPJ" | tr '[:upper:]' '[:lower:]')
DIR=$(dirname "$0")
sed -e "s/__CNPJ__/$CNPJ/g" -e "s/__cnpj__/$cnpj/g" "$DIR/modelo-empresa.yaml"
