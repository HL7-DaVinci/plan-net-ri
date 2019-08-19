#!/bin/sh

mvn package && \
  docker build -t plan_net .

