#!/usr/bin/env bash
#
# Fixes the changelog of a release, and opens the next one. Called by .github/workflows/release.yaml;
# safe to run by hand.
#
#   schema-version.sh pin 2.0.0    renames latest.xml to 2.0.0.xml, points the master changelog at it
#                                  and writes 2.0.0.xml.sha256 - from then on the build fails if the
#                                  file is edited
#   schema-version.sh open 2.1.0   creates an empty latest.xml for the next version and includes it
#
# Why: a changeset which was applied to somebody's database must never change afterwards, because
# Liquibase compares checksums and refuses to run. Splitting the versions into files and pinning the
# released ones turns that rule into something the build enforces.
set -euo pipefail

command="${1:-}"
version="${2:-}"
script_dir="$(dirname "${BASH_SOURCE[0]}")"
schema_dir="${script_dir}/../src/main/resources/vanillabp/schema"
master="${schema_dir}/changelog.xml"
latest="${schema_dir}/latest.xml"

usage() {
  echo "usage: $(basename "$0") {pin|open} <version>" >&2
  exit 2
}

checksum_of() {
  sha256sum "$1" | cut -d' ' -f1
}

case "${command}" in

  pin)
    [ -n "${version}" ] || usage
    [ -f "${latest}" ] || { echo "no latest.xml in ${schema_dir} - is this release already pinned?" >&2; exit 1; }
    released="${schema_dir}/${version}.xml"
    [ -e "${released}" ] && { echo "${released} exists already - a released changelog is never overwritten" >&2; exit 1; }

    mv "${latest}" "${released}"
    # the include of latest.xml becomes the include of the released file, and it moves above the
    # comment which marks where the next one goes
    python3 - "${master}" "${version}" <<'PYTHON'
import sys
master, version = sys.argv[1], sys.argv[2]
text = open(master).read()
latest_include = '  <!-- the version under development -->\n  <include file="vanillabp/schema/latest.xml"/>\n'
if latest_include not in text:
    raise SystemExit('the include of latest.xml is not where it was expected in %s' % master)
released_include = '  <include file="vanillabp/schema/%s.xml"/>\n' % version
marker = '  <!-- released versions, oldest first; the release adds a line here and never touches an existing\n       one -->\n'
if marker not in text:
    raise SystemExit('the marker for released versions is missing in %s' % master)
text = text.replace(marker, marker + released_include, 1)
text = text.replace('\n' + latest_include, '', 1)
open(master, 'w').write(text)
PYTHON

    checksum_of "${released}" > "${released}.sha256"
    echo "pinned ${version}.xml with checksum $(cat "${released}.sha256")"
    ;;

  open)
    [ -n "${version}" ] || usage
    [ -e "${latest}" ] && { echo "${latest} exists already" >&2; exit 1; }

    cat > "${latest}" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<!--
  The changesets of VanillaBP ${version}, the version under development. Append changesets here;
  never touch a file of a released version, its checksum is pinned (see the module's README).

  Every file here declares the SAME logicalFilePath, which is why the release can rename this file
  to its version without changing anything Liquibase records.
-->
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd"
    logicalFilePath="vanillabp/schema">

  <!-- no change to the schema in ${version} yet -->

</databaseChangeLog>
XML

    python3 - "${master}" <<'PYTHON'
import sys
master = sys.argv[1]
text = open(master).read()
include = '  <!-- the version under development -->\n  <include file="vanillabp/schema/latest.xml"/>\n'
if include in text:
    raise SystemExit(0)
closing = '\n</databaseChangeLog>\n'
if closing not in text:
    raise SystemExit('unexpected end of %s' % master)
text = text.replace(closing, '\n' + include + closing, 1)
open(master, 'w').write(text)
PYTHON
    echo "opened ${version} in latest.xml"
    ;;

  *)
    usage
    ;;

esac
