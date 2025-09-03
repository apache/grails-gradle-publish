#!/usr/bin/env bash
#
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.
#
set -euo pipefail

RELEASE_TAG=$1
DOWNLOAD_LOCATION="${2:-downloads}"
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

if [ -z "${RELEASE_TAG}" ]; then
  echo "Usage: $0 [release-tag] <optional download location>"
  exit 1
fi

VERSION=${RELEASE_TAG#v}

cd "${DOWNLOAD_LOCATION}"
ZIP_FILE=$(ls "apache-grails-publish-${VERSION}-incubating-src.zip" 2>/dev/null | head -n 1)

if [ -z "${ZIP_FILE}" ]; then
  echo "Error: Could not find apache-grails-publish-${VERSION}-incubating-src.zip in ${DOWNLOAD_LOCATION}"
  exit 1
fi

export GRAILS_GPG_HOME=$(mktemp -d)
cleanup() {
  rm -rf "${GRAILS_GPG_HOME}"
}
trap cleanup EXIT

echo "Verifying checksum..."
shasum -a 512 -c "apache-grails-publish-${VERSION}-incubating-src.zip.sha512"
echo "✅ Checksum Verified"

echo "Importing GPG key to independent GPG home ..."
gpg --homedir "${GRAILS_GPG_HOME}" --import "${SCRIPT_DIR}/../../KEYS"
echo "✅ GPG Key Imported"

echo "Verifying GPG signature..."
gpg --homedir "${GRAILS_GPG_HOME}" --verify "apache-grails-publish-${VERSION}-incubating-src.zip.asc" "apache-grails-publish-${VERSION}-incubating-src.zip"
echo "✅ GPG Verified"

SRC_DIR="grails-publish"

if [ -d "${SRC_DIR}" ]; then
  echo "Previous grails directory found, purging"
  cd grails-publish
  find . -mindepth 1 -path ./etc -prune -o -exec rm -rf {} + || true
  cd etc
  find . -mindepth 1 -path ./bin -prune -o -exec rm -rf {} + || true
  cd bin
  find . -mindepth 1 -path ./results -prune -o -exec rm -rf {} +|| true
  cd "${DOWNLOAD_LOCATION}"
fi
echo "Extracting zip file..."
unzip -q "apache-grails-publish-${VERSION}-incubating-src.zip"

if [ ! -d "${SRC_DIR}" ]; then
  echo "Error: Expected extracted folder '${SRC_DIR}' not found."
  exit 1
fi

echo "Checking for required files existence..."
REQUIRED_FILES=("LICENSE" "NOTICE" "README.md" "PUBLISHED_ARTIFACTS" "CHECKSUMS" "BUILD_DATE" "DISCLAIMER")

for FILE in "${REQUIRED_FILES[@]}"; do
  if [ ! -f "${SRC_DIR}/$FILE" ]; then
    echo "❌ Missing required file: $FILE"
    exit 1
  fi

  echo "✅ Found required file: $FILE"
done

echo "✅ All source distribution checks passed successfully for Apache Grails Gradle Publish ${VERSION}."