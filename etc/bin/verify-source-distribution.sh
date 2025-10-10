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

PROJECT_NAME='grails-publish'
RELEASE_TAG=$1
DOWNLOAD_LOCATION="${2:-downloads}"
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

if [ -z "${RELEASE_TAG}" ]; then
  echo "Usage: $0 [release-tag] <optional download location>"
  exit 1
fi

VERSION=${RELEASE_TAG#v}

cd "${DOWNLOAD_LOCATION}"
ZIP_FILE=$(ls "apache-${PROJECT_NAME}-${VERSION}-src.zip" 2>/dev/null | head -n 1)

if [ -z "${ZIP_FILE}" ]; then
  echo "Error: Could not find apache-${PROJECT_NAME}-${VERSION}-src.zip in ${DOWNLOAD_LOCATION}"
  exit 1
fi

export GRAILS_GPG_HOME=$(mktemp -d)
cleanup() {
  rm -rf "${GRAILS_GPG_HOME}"
}
trap cleanup EXIT

echo "Verifying checksum..."
shasum -a 512 -c "apache-${PROJECT_NAME}-${VERSION}-src.zip.sha512"
echo "✅ Checksum Verified"

echo "Importing GPG key to independent GPG home ..."
gpg --homedir "${GRAILS_GPG_HOME}" --import "${DOWNLOAD_LOCATION}/KEYS"
echo "✅ GPG Key Imported"

echo "Verifying GPG signature..."
gpg --homedir "${GRAILS_GPG_HOME}" --verify "apache-${PROJECT_NAME}-${VERSION}-src.zip.asc" "apache-${PROJECT_NAME}-${VERSION}-src.zip"
echo "✅ GPG Verified"

SRC_DIR="${PROJECT_NAME}"

if [ -d "${SRC_DIR}" ]; then
  echo "Previous ${SRC_DIR} directory found, purging"
  cd "${SRC_DIR}"
  find . -mindepth 1 -path ./etc -prune -o -exec rm -rf {} + || true
  cd etc
  find . -mindepth 1 -path ./bin -prune -o -exec rm -rf {} + || true
  cd bin
  find . -mindepth 1 -path ./results -prune -o -exec rm -rf {} +|| true
  cd "${DOWNLOAD_LOCATION}"
fi
echo "Extracting zip file..."
unzip -q "apache-${PROJECT_NAME}-${VERSION}-src.zip"

if [ ! -d "${SRC_DIR}" ]; then
  echo "Error: Expected extracted folder '${SRC_DIR}' not found."
  exit 1
fi

echo "Checking for required files existence..."
REQUIRED_FILES=("LICENSE" "NOTICE" "README.md" "PUBLISHED_ARTIFACTS" "CHECKSUMS" "BUILD_DATE")

for FILE in "${REQUIRED_FILES[@]}"; do
  if [ ! -f "${SRC_DIR}/$FILE" ]; then
    echo "❌ Missing required file: $FILE"
    exit 1
  fi

  echo "✅ Found required file: $FILE"
done

echo "✅ All source distribution checks passed successfully for Apache Grails - Gradle Plugin - Grails Publish ${VERSION}."