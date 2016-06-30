#!/usr/bin/env bash

java -jar ./target/leansw-rancher-service-upgrader-1.0-SNAPSHOT.jar \
--rancher.uri=$RANCHER_URI \
--app.secret=$RANCHER_API_SECRET \
--secret.key=$RANCHER_SECRET_KEY \
--environment.name="Default" \
--stack.name="deliflow-ui-and-services" \
--service.name="deliflow-webapp"

