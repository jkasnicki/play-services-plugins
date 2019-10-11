/**
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.oss.licenses.plugin

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.slf4j.LoggerFactory

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Task to find available licenses from the artifacts stored in the json
 * file generated by DependencyTask, and then generate the third_party_licenses
 * and third_party_license_metadata file.
 */
class LicensesTask extends DefaultTask {
    private static final String UTF_8 = "UTF-8"
    private static final byte[] LINE_SEPARATOR = System
            .getProperty("line.separator").getBytes(UTF_8)
    private static final int GRANULAR_BASE_VERSION = 14
    private static final String GOOGLE_PLAY_SERVICES_GROUP =
        "com.google.android.gms"
    private static final String LICENSE_ARTIFACT_SURFIX = "-license"
    private static final String FIREBASE_GROUP = "com.google.firebase"
    private static final String FAIL_READING_LICENSES_ERROR =
        "Failed to read license text."

    private static final logger = LoggerFactory.getLogger(LicensesTask.class)

    protected int start = 0
    protected Set<String> googleServiceLicenses = []
    protected Map<String, String> licensesMap = [:]

    @InputFile
    public File dependenciesJson

    @OutputDirectory
    public File outputDir

    @OutputFile
    public File licenses

    @OutputFile
    public File licensesMetadata

    @TaskAction
    void action() {
        initOutputDir()
        initLicenseFile()
        initLicensesMetadata()

        def allDependencies = new JsonSlurper().parse(dependenciesJson)
        for (entry in allDependencies) {
            String group = entry.group
            String name = entry.name
            String fileLocation = entry.fileLocation
            String version = entry.version
            File artifactLocation = new File(fileLocation)

            if (isGoogleServices(group, name)) {
                // Add license info for google-play-services itself
                if (!name.endsWith(LICENSE_ARTIFACT_SURFIX)) {
                    addLicensesFromPom(group, name, version)
                }
                // Add transitive licenses info for google-play-services. For
                // post-granular versions, this is located in the artifact
                // itself, whereas for pre-granular versions, this information
                // is located at the complementary license artifact as a runtime
                // dependency.
                if (isGranularVersion(version)) {
                    addGooglePlayServiceLicenses(artifactLocation)
                } else if (name.endsWith(LICENSE_ARTIFACT_SURFIX)) {
                    addGooglePlayServiceLicenses(artifactLocation)
                }
            } else {
                addLicensesFromPom(group, name, version)
            }
        }

        writeMetadata()
    }

    protected void initOutputDir() {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
    }

    protected void initLicenseFile() {
        if (licenses == null) {
            logger.error("License file is undefined")
        }
        licenses.newWriter().withWriter {w ->
            w << ''
        }
    }

    protected void initLicensesMetadata() {
        licensesMetadata.newWriter().withWriter {w ->
            w << ''
        }
    }

    protected boolean isGoogleServices(String group, String name) {
        return (GOOGLE_PLAY_SERVICES_GROUP.equalsIgnoreCase(group)
                || FIREBASE_GROUP.equalsIgnoreCase(group))
    }

    protected boolean isGranularVersion (String version) {
        String[] versions = version.split("\\.")
        return (versions.length > 0
                && Integer.valueOf(versions[0]) >= GRANULAR_BASE_VERSION)
    }

    protected void addGooglePlayServiceLicenses(File artifactFile) {
        ZipFile licensesZip = new ZipFile(artifactFile)
        JsonSlurper jsonSlurper = new JsonSlurper()

        ZipEntry jsonFile = licensesZip.getEntry("third_party_licenses.json")
        ZipEntry txtFile = licensesZip.getEntry("third_party_licenses.txt")

        if (!jsonFile || !txtFile) {
            return
        }

        Object licensesObj = jsonSlurper.parse(licensesZip.getInputStream(
            jsonFile))
        if (licensesObj == null) {
            return
        }

        for (entry in licensesObj) {
            String key = entry.key
            int startValue = entry.value.start
            int lengthValue = entry.value.length

            if (!googleServiceLicenses.contains(key)) {
                googleServiceLicenses.add(key)
                byte[] content = getBytesFromInputStream(
                    licensesZip.getInputStream(txtFile),
                    startValue,
                    lengthValue)
                appendLicense(key, content)
            }
        }
    }

    protected byte[] getBytesFromInputStream(
        InputStream stream,
        long offset,
        int length) {
        try {
            byte[] buffer = new byte[1024]
            ByteArrayOutputStream textArray = new ByteArrayOutputStream()

            stream.skip(offset)
            int bytesRemaining = length > 0 ? length : Integer.MAX_VALUE
            int bytes = 0

            while (bytesRemaining > 0
                && (bytes =
                stream.read(
                    buffer,
                    0,
                    Math.min(bytesRemaining, buffer.length)))
                != -1) {
                textArray.write(buffer, 0, bytes)
                bytesRemaining -= bytes
            }
            stream.close()

            return textArray.toByteArray()
        } catch (Exception e) {
            throw new RuntimeException(FAIL_READING_LICENSES_ERROR, e)
        }
    }

    protected void addLicensesFromPom(String group, String name, String version) {
        def pomFile = resolvePomFileArtifact(group, name, version)
        addLicensesFromPom(pomFile, group, name)
    }

    protected void addLicensesFromPom(File pomFile, String group, String name) {
        if (pomFile == null || !pomFile.exists()) {
            logger.error("POM file $pomFile does not exist.")
            return
        }

        def rootNode = new XmlSlurper().parse(pomFile)
        if (rootNode.licenses.size() == 0) {
            return
        }

        String licenseKey = "${group}:${name}"
        if (rootNode.licenses.license.size() > 1) {
            rootNode.licenses.license.each { node ->
                String nodeName = node.name
                String nodeUrl = node.url
                appendLicense("${licenseKey} ${nodeName}", nodeUrl.getBytes(UTF_8))
            }
        } else {
            String nodeUrl = rootNode.licenses.license.url
            appendLicense(licenseKey, nodeUrl.getBytes(UTF_8))
        }
    }

    protected File resolvePomFileArtifact(String group, String name, String version) {
        def moduleComponentIdentifier =
                createModuleComponentIdentifier(group, name, version)
        logger.info("Resolving POM file for $moduleComponentIdentifier licenses.")
        def components = getProject().getDependencies()
                .createArtifactResolutionQuery()
                .forComponents(moduleComponentIdentifier)
                .withArtifacts(MavenModule.class, MavenPomArtifact.class)
                .execute()
        if (components.resolvedComponents.isEmpty()) {
            logger.warn("$moduleComponentIdentifier has no POM file.")
            return null
        }

        def artifacts = components.resolvedComponents[0].getArtifacts(MavenPomArtifact.class)
        if (artifacts.isEmpty()) {
            logger.error("$moduleComponentIdentifier empty POM artifact list.")
            return null
        }
        if (!(artifacts[0] instanceof ResolvedArtifactResult)) {
            logger.error("$moduleComponentIdentifier unexpected type ${artifacts[0].class}")
            return null
        }
        return ((ResolvedArtifactResult) artifacts[0]).getFile()
    }

    protected void appendLicense(String key, byte[] content) {
        if (licensesMap.containsKey(key)) {
            return
        }

        licensesMap.put(key, "${start}:${content.length}")
        appendLicenseContent(content)
        appendLicenseContent(LINE_SEPARATOR)
    }

    protected void appendLicenseContent(byte[] content) {
        licenses.append(content)
        start += content.length
    }

    protected void writeMetadata() {
        for (entry in licensesMap) {
            licensesMetadata.append("$entry.value $entry.key", UTF_8)
            licensesMetadata.append(LINE_SEPARATOR)
        }
    }

    private static ModuleComponentIdentifier createModuleComponentIdentifier(String group, String name, String version) {
        return new DefaultModuleComponentIdentifier(DefaultModuleIdentifier.newId(group, name), version)
    }

}
