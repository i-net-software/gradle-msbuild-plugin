package com.ullink

import com.google.common.io.Files
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

class AssemblyInfoVersionPatcher extends DefaultTask {
    @InputFiles
    ListProperty<File> files

    @Input
    final ListProperty<String> projects

    AssemblyInfoVersionPatcher() {
        projects = project.getObjects().listProperty(String)
        projects.set(project.provider({
            try {
                def msbuildTask = project.tasks.findByName('msbuild')
                if (msbuildTask == null) {
                    project.logger.debug("AssemblyInfoPatcher: msbuild task not found, using empty list")
                    return []
                }
                msbuildTask.projects.collect { it.key }
            } catch (Exception e) {
                // If accessing msbuild.projects fails (e.g., dotnet not available), return empty list
                // This allows the build to continue without project parsing
                project.logger.debug("AssemblyInfoPatcher: Could not access msbuild.projects, using empty list. Error: ${e.message}")
                []
            }
        }))

        files = project.getObjects().listProperty(File)
        files.set(project.provider({
            try {
                def msbuildTask = project.tasks.findByName('msbuild')
                if (msbuildTask == null) {
                    project.logger.debug("AssemblyInfoPatcher: msbuild task not found, using empty file list")
                    return []
                }
                projects.get()
                    .collect { 
                        try {
                            msbuildTask.projects[it]
                        } catch (Exception e) {
                            project.logger.debug("AssemblyInfoPatcher: Could not access project '${it}', skipping. Error: ${e.message}")
                            null
                        }
                    }
                    .findAll { it != null }
                    .collect {
                        try {
                            def result
                            if (it.properties.UsingMicrosoftNETSdk == 'true') {
                                result = it.properties.MSBuildProjectFullPath
                            } else {
                                result = it?.getItems('Compile')?.find { Files.getNameWithoutExtension(it.name) == 'AssemblyInfo' }
                            }
                            result
                        } catch (Exception e) {
                            project.logger.debug("AssemblyInfoPatcher: Error processing project file, skipping. Error: ${e.message}")
                            null
                        }
                    }
                    .findAll { it != null }
                    .unique()
                    .collect {
                        project.logger.info("AssemblyInfoPatcher: found file ${it} (${it?.class})")
                        project.file(it)
                    }
            } catch (Exception e) {
                // If file resolution fails, return empty list
                project.logger.debug("AssemblyInfoPatcher: Could not resolve files, using empty list. Error: ${e.message}")
                []
            }
        }))

        fileVersion = project.getObjects().property(String)
        informationalVersion = project.getObjects().property(String)
        fileVersion.set(project.provider ({ version }))
        informationalVersion.set(project.provider ({ version }))
        enabled = false
    }

    private String versionValue
    @Input
    String getVersion() {
        return versionValue
    }

    void setVersion(String version) {
        versionValue = version
        enabled = version != null
    }

    @Input
    final Property<String> fileVersion

    @Input
    final Property<String> informationalVersion

    @Input
    String title = ''

    @Input
    String company = ''

    @Input
    String product = ''

    @Input
    String copyright = ''

    @Input
    String trademark = ''

    @Input
    String assemblyDescription = ''

    @Input
    def charset = 'UTF-8'

    @TaskAction
    void run() {
        files.get().each {
            logger.info("Replacing version attributes in $it")
            replace(it, 'AssemblyVersion', version)
            replace(it, 'AssemblyFileVersion', fileVersion.get())
            replace(it, 'AssemblyInformationalVersion', informationalVersion.get())
            replace(it, 'AssemblyDescription', assemblyDescription)
            replace(it, 'AssemblyTitle', title)
            replace(it, 'AssemblyCompany', company)
            replace(it, 'AssemblyProduct', product)
            replace(it, 'AssemblyCopyright', copyright)
            replace(it, 'AssemblyTrademark', trademark)
        }
    }

    void replace(File file, def name, def value) {
        // only change the assembly values if they specified here (not blank or null)
        // if the parameters are blank, then keep whatever is already in the assemblyInfo file.
        if (!value) {
            return
        }

        def extension = Files.getFileExtension(file.absolutePath)
        switch (extension) {
            case 'fs':
                project.ant.replaceregexp(file: file, match: /^\[<assembly: $name\s*\(".*"\)\s*>\]$/, replace: "[<assembly: ${name}(\"${value}\")>]", byline: true, encoding: charset)
                break
            case 'vb':
                project.ant.replaceregexp(file: file, match: /^\[<assembly: $name\s*\(".*"\)\s*>\]$/, replace: "[<assembly: ${name}(\"${value}\")>]", byline: true, encoding: charset)
                break
            // project file
            case ~/.*proj$/:
                if (name != 'AssemblyVersion' && name != 'AssemblyTitle' && name.startsWith('Assembly')) {
                    name = name.substring('Assembly'.length())
                }
                project.ant.replaceregexp(file: file, match: /<$name>\s*([^\s]+)\s*\<\/$name>$/, replace: "<$name>$value</$name>", byline: true, encoding: charset)
                break
            default:
                project.ant.replaceregexp(file: file, match: /^\[assembly: $name\s*\(".*"\)\s*\]$/, replace: "[assembly: ${name}(\"${value}\")]", byline: true, encoding: charset)
                break

        }
    }
}
