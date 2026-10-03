#!/usr/bin/env bash
cd /home/vscode/worktrees/889-start-entry || exit 1
./mvnw -Dmaven.repo.local=$HOME/.m2/repo-889 com.diffplug.spotless:spotless-maven-plugin:apply > .logs/spotless2.log 2>&1
./mvnw -Dmaven.repo.local=$HOME/.m2/repo-889 \
  -pl migration-adapter/integration-spi,migration-adapter/adapter-spi,migration-adapter/extension-spi,migration-adapter/runtime,schema,test-utils,spring-boot-integration/runtime,quarkus-integration/runtime \
  -am -DskipITs install > .logs/verify.log 2>&1
echo "=== VERIFY DONE exit=$? ===" >> .logs/verify.log
