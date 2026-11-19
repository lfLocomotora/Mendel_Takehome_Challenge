#!/bin/sh
set -eu

image=${1:-mendel-transactions:local}
container="mendel-smoke-$$"
temporary=$(mktemp -d)

cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then
    docker logs "$container" >&2 2>/dev/null || true
  fi
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$temporary"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker run --detach --name "$container" --publish 127.0.0.1::8080 "$image" >/dev/null
port=$(docker port "$container" 8080/tcp)
base="http://$port"

wait_until_ready() {
  attempt=0
  until curl --silent --fail --connect-timeout 1 --max-time 2 \
    "$base/transactions/types/readiness" >/dev/null; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
      echo 'Application did not become ready.' >&2
      exit 1
    fi
    sleep 1
  done
}

request() {
  expected=$1
  method=$2
  path=$3
  body=${4:-}
  status=$(curl --silent --show-error --connect-timeout 2 --max-time 10 \
    --dump-header "$temporary/headers" \
    --output "$temporary/body" --write-out '%{http_code}' \
    --request "$method" --header 'Content-Type: application/json' \
    --data "$body" "$base$path")
  if [ "$status" != "$expected" ]; then
    echo "Expected HTTP $expected, received $status for $method $path" >&2
    cat "$temporary/body" >&2
    exit 1
  fi
}

expect_body() {
  actual=$(cat "$temporary/body")
  if [ "$actual" != "$1" ]; then
    echo "Expected $1, received $actual" >&2
    exit 1
  fi
}

wait_until_ready
test "$(docker exec "$container" id -u)" = 10001
request 201 PUT /transactions/10 '{"amount":5000,"type":"Cars"}'
expect_body '{"status":"ok"}'
request 201 PUT /transactions/11 '{"amount":10000,"type":"shopping","parent_id":10}'
request 201 PUT /transactions/12 '{"amount":5000,"type":"shopping","parent_id":11}'
request 200 GET /transactions/types/CARS
expect_body '[10]'
request 200 GET /transactions/sum/10
expect_body '{"sum":20000.0}'
request 200 GET /transactions/sum/11
expect_body '{"sum":15000.0}'
request 200 GET '/transactions/types/shopping?limit=1'
expect_body '[11]'
next=$(sed -n 's/^[Ll]ink: <\([^>]*\)>; rel="next".*/\1/p' "$temporary/headers")
test -n "$next"
request 200 GET "$next"
expect_body '[12]'
if [ -n "$(sed -n '/^[Ll]ink:/p' "$temporary/headers")" ]; then
  echo 'The last page must not include a next link.' >&2
  exit 1
fi
request 409 PUT /transactions/10 '{"amount":1,"type":"cars","parent_id":12}'
case $(cat "$temporary/body") in
  *'"code":"CYCLE_DETECTED"'*) ;;
  *) echo 'Expected a structured cycle error.' >&2; exit 1 ;;
esac
request 200 GET /transactions/sum/10
expect_body '{"sum":20000.0}'
request 200 PUT /transactions/12 '{"amount":-5,"type":"adjustment"}'
request 200 GET /transactions/sum/10
expect_body '{"sum":15000.0}'

request 201 PUT /transactions/13 '{"amount":1,"type":"food/drink"}'
request 201 PUT /transactions/14 '{"amount":2,"type":"food/drink"}'
request 200 GET '/transactions/types/food%2Fdrink?limit=1'
expect_body '[13]'
next=$(sed -n 's/^[Ll]ink: <\([^>]*\)>; rel="next".*/\1/p' "$temporary/headers")
test -n "$next"
request 200 GET "$next"
expect_body '[14]'

request 400 PUT /transactions/15 '{"amount":99,"type":"."}'
request 400 PUT /transactions/10 '{"amount":99,"type":"a\u0000b"}'
request 400 PUT /transactions/10 '{"amount":99,"type":"\uD800"}'
oversized_type=$(printf '%1025s' '' | tr ' ' 'a')
request 400 PUT /transactions/10 "{\"amount\":99,\"type\":\"$oversized_type\"}"
request 200 GET /transactions/sum/10
expect_body '{"sum":15000.0}'

docker restart "$container" >/dev/null
port=$(docker port "$container" 8080/tcp)
base="http://$port"
wait_until_ready
request 200 GET /transactions/types/cars
expect_body '[]'
request 404 GET /transactions/sum/10
echo 'Docker smoke test passed: API, pagination, validation, replacement and empty restart.'
