///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21
//DEPS org.apache.maven:maven-model:4.0.0-rc-5
//DEPS org.semver4j:semver4j:5.6.0

import org.apache.maven.api.model.Model;
import org.apache.maven.model.v4.MavenStaxReader;
import org.semver4j.Semver;

import java.io.FileReader;

/**
 * Computes the release version (current pom version with any pre-release/SNAPSHOT suffix
 * stripped) and the next development version (patch bump + "-SNAPSHOT") from the root
 * pom.xml's current version. Companion to UpdateVersion.java for bin/push-version.sh.
 *
 * Usage:
 *   jbang bin/NextVersion.java   prints "<release> <next-development>" on one line
 */
class NextVersion {
    public static void main(String[] args) throws Exception {
        var mavenStaxReader = new MavenStaxReader();
        Model read = mavenStaxReader.read(new FileReader("pom.xml"));
        String pomVersion = read.getVersion();
        String release;
        Object next;
        if (pomVersion.indexOf('.') > 0) {
            var releaseVersion = Semver.parse(pomVersion).withClearedPreRelease();
            release = releaseVersion.toString();
            next = releaseVersion.withIncPatch().withPreRelease("SNAPSHOT");
        } else {
            release = pomVersion.replace("-SNAPSHOT", "");
            next = (Integer.parseInt(release) + 1) + "-SNAPSHOT";
        }

        System.out.println(release + " " + next);
    }
}
