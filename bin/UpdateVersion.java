///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21
//DEPS org.apache.maven:maven-model:4.0.0-rc-5

import org.apache.maven.api.model.Model;
import org.apache.maven.model.v4.MavenStaxReader;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Updates every pom.xml, build.gradle.kts, and README.md example version in this project to
 * a target version.
 *
 * Exists because the versions-maven-plugin doesn't recognize the modelVersion 4.1.0
 * {@code <subprojects>} element cartographer's poms use in place of {@code <modules>}, so
 * `mvn versions:set` silently only updates the invoking pom and never touches the child
 * modules' `<parent><version>`.
 *
 * Usage: jbang bin/UpdateVersion.java <target-version>
 */
class UpdateVersion {

    static final String GROUP_ID = "com.antwerkz";
    static final String ROOT_ARTIFACT = "cartographer";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: jbang UpdateVersion.java <target-version>");
            System.exit(1);
        }
        String targetVersion = args[0];
        Path root = Path.of(".").toAbsolutePath().normalize();

        updatePoms(root, targetVersion);
        updateGradleFiles(root, targetVersion);
        updateReadmes(root, targetVersion);
    }

    static void updatePoms(Path root, String targetVersion) throws Exception {
        var reader = new MavenStaxReader();
        List<Path> poms;
        try (Stream<Path> walk = Files.walk(root)) {
            poms = walk
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                .filter(p -> !p.toString().contains(File.separator + "src" + File.separator))
                .sorted()
                .toList();
        }

        for (Path pom : poms) {
            Model model;
            try (FileReader fr = new FileReader(pom.toFile())) {
                model = reader.read(fr);
            }

            boolean isChild = model.getParent() != null
                && GROUP_ID.equals(model.getParent().getGroupId())
                && ROOT_ARTIFACT.equals(model.getParent().getArtifactId());

            // The root's own groupId/artifactId are inherited from its (external) parent,
            // so read them off the parent when unset rather than requiring parent == null.
            String groupId = model.getGroupId() != null
                ? model.getGroupId()
                : model.getParent() != null ? model.getParent().getGroupId() : null;
            boolean isRoot = !isChild
                && GROUP_ID.equals(groupId)
                && ROOT_ARTIFACT.equals(model.getArtifactId());

            if (isRoot) {
                String oldVersion = model.getVersion();
                if (!oldVersion.equals(targetVersion)) {
                    updateOwnVersion(pom, oldVersion, targetVersion);
                    System.out.println("pom:    " + pom + "  " + oldVersion + " -> " + targetVersion);
                }
            } else if (isChild) {
                String oldVersion = model.getParent().getVersion();
                if (!oldVersion.equals(targetVersion)) {
                    updateParentVersion(pom, oldVersion, targetVersion);
                    System.out.println("pom:    " + pom + "  (parent) " + oldVersion + " -> " + targetVersion);
                }
            }
        }
    }

    static void updateOwnVersion(Path pom, String oldVersion, String newVersion) throws IOException {
        String content = Files.readString(pom);
        int parentEnd = content.indexOf("</parent>");
        int searchFrom = parentEnd >= 0 ? parentEnd : 0;
        String before = content.substring(0, searchFrom);
        String after = content.substring(searchFrom);

        String updatedAfter = after.replaceFirst(
            "(<version>)" + Pattern.quote(oldVersion) + "(</version>)",
            "$1" + Matcher.quoteReplacement(newVersion) + "$2"
        );
        if (updatedAfter.equals(after)) {
            throw new IllegalStateException("Could not find <version>" + oldVersion + "</version> to replace in " + pom);
        }
        Files.writeString(pom, before + updatedAfter);
    }

    static void updateParentVersion(Path pom, String oldVersion, String newVersion) throws IOException {
        String content = Files.readString(pom);
        int parentStart = content.indexOf("<parent>");
        int parentEndTag = content.indexOf("</parent>");
        if (parentStart < 0 || parentEndTag < 0) {
            throw new IllegalStateException("No <parent> block found in " + pom);
        }
        int parentEnd = parentEndTag + "</parent>".length();
        String before = content.substring(0, parentStart);
        String parentBlock = content.substring(parentStart, parentEnd);
        String after = content.substring(parentEnd);

        String updatedParentBlock = parentBlock.replaceFirst(
            "(<version>)" + Pattern.quote(oldVersion) + "(</version>)",
            "$1" + Matcher.quoteReplacement(newVersion) + "$2"
        );
        if (updatedParentBlock.equals(parentBlock)) {
            throw new IllegalStateException("Could not find parent <version>" + oldVersion + "</version> to replace in " + pom);
        }
        Files.writeString(pom, before + updatedParentBlock + after);
    }

    static void updateGradleFiles(Path root, String newVersion) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk
                .filter(p -> p.getFileName().toString().equals("build.gradle.kts"))
                .filter(p -> !p.toString().contains(File.separator + "build" + File.separator))
                .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                .toList();
        }

        Pattern versionAssignment = Pattern.compile("(?m)^(version\\s*=\\s*\")[^\"]+(\")");
        for (Path file : files) {
            String content = Files.readString(file);
            String updated = versionAssignment.matcher(content)
                .replaceFirst("$1" + Matcher.quoteReplacement(newVersion) + "$2");
            if (!updated.equals(content)) {
                Files.writeString(file, updated);
                System.out.println("gradle: " + file + "  -> " + newVersion);
            }
        }
    }

    static void updateReadmes(Path root, String newVersion) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk
                .filter(p -> p.getFileName().toString().equals("README.md"))
                .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                .toList();
        }

        // Only touches <version> tags that immediately follow an <artifactId> for one of our
        // own published artifacts, e.g. in a "how to depend on this plugin" example snippet.
        // Avoids blanket-matching every semver-looking string in prose.
        Pattern examplePluginVersion = Pattern.compile(
            "(<artifactId>cartographer-(?:maven-plugin|agent)</artifactId>\\s*\\n\\s*<version>)[^<]+(</version>)"
        );

        for (Path file : files) {
            String content = Files.readString(file);
            String updated = examplePluginVersion.matcher(content)
                .replaceAll("$1" + Matcher.quoteReplacement(newVersion) + "$2");
            if (!updated.equals(content)) {
                Files.writeString(file, updated);
                System.out.println("readme: " + file + "  -> " + newVersion);
            }
        }
    }
}
