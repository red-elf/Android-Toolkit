#!/bin/sh

if [ -z "$1" ]; then

  echo "Error: No Docker recipes path argument provided"
  exit 1
fi

# TODO: Create user
# sudo htpasswd -c /etc/squid/passwords username

if [ -z "$PROXY_LOCAL_PROD_PORT" ]; then

  echo "Error: PROXY_LOCAL_PROD_PORT is not set"
  exit 1
fi

if [ -z "$PROXY_LOCAL_PROD_USERNAME" ]; then

  echo "Error: PROXY_LOCAL_PROD_USERNAME is not set"
  exit 1
fi

if [ -z "$PROXY_LOCAL_PROD_PASSWORD" ]; then

  echo "Error: PROXY_LOCAL_PROD_PASSWORD is not set"
  exit 1
fi

if cd "$1" && docker login && \
  docker-compose up -d && docker ps; then # TODO: Pass the environment variables values to the docker compose

  if curl -x "http://127.0.0.1:$PROXY_LOCAL_PROD_PORT" -U "$PROXY_LOCAL_PROD_USERNAME:$PROXY_LOCAL_PROD_PASSWORD" https://www.github.com; then

    echo "Squid proxy is running and accessible at http://127.0.0.1:$PROXY_LOCAL_PROD_PORT"

  else

    echo "Error: Failed to access GitHub website using proxy."
    exit 1
  fi

else

  echo "Error: Failed to start squid container."
  exit 1
fi