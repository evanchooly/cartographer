#! /bin/bash
set -e

if [ ! -e pom.xml ]
then
	echo "This must be run from the project root"
	exit 1
fi

RELEASE=$( jbang --quiet `dirname ${0}`/NextVersion.java current )
DEVELOP=$( jbang --quiet `dirname ${0}`/NextVersion.java )

echo "Release version = ${RELEASE}"
echo "Next version    = ${DEVELOP}"

echo press enter to continue
read

jbang `dirname ${0}`/UpdateVersion.java ${RELEASE}

mvn clean install -DskipTests

git commit -a -m "bump to release version $RELEASE"
git tag v$RELEASE

jbang `dirname ${0}`/UpdateVersion.java ${DEVELOP}

git commit -a -m "bump to development version $DEVELOP"

git push
git push --tags
