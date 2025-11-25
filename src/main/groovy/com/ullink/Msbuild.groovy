package com.ullink
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.file.Files
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.internal.ConventionTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations
import javax.inject.Inject

class OldProjectFormatException extends Exception {
    OldProjectFormatException(String message) {
        super(message)
    }
}

class Msbuild extends ConventionTask {

    @Input @Optional
    String version
    @Input @Optional
    String msbuildDir
    @Input @Optional
    def solutionFile
    @Input @Optional
    def projectFile
    @Input @Optional
    String loggerAssembly
    @Input @Optional
    Boolean optimize
    @Input @Optional
    Boolean debugSymbols
    @Input @Optional
    String debugType
    @Input @Optional
    String platform
    @Input @Optional
    def destinationDir
    @Input @Optional
    def intermediateDir
    @Input @Optional
    Boolean generateDoc
    @Input @Optional
    String projectName
    @Input @Optional
    String configuration
    @Input @Optional
    List<String> defineConstants
    @Input @Optional
    List<String> targets
    @Input @Optional
    String verbosity
    @Input @Optional
    Map<String, Object> parameters = [:]
    @Input @Optional
    Map<String, ProjectFileParser> allProjects = [:]
    @Input @Optional
    String executable
    @Internal
    ProjectFileParser projectParsed
    @Internal
    IExecutableResolver resolver
    @Internal
    Boolean parseProject = true
    @Inject
    ExecOperations getExecOps() {}

    Msbuild() {
        description = 'Executes MSBuild on the specified project/solution'
        resolver =
                OperatingSystem.current().windows ? new MsbuildResolver() : new XbuildResolver()

        conventionMapping.map "solutionFile", {
            project.file(project.name + ".sln").exists() ? project.name + ".sln" : null
        }
        conventionMapping.map "projectFile", {
            project.file(project.name + ".csproj").exists() ? project.name + ".csproj" : null
        }
        conventionMapping.map "projectName", { project.name }
    }

    @Internal
    boolean isSolutionBuild() {
        projectFile == null && getSolutionFile() != null
    }

    @Internal
    boolean isProjectBuild() {
        solutionFile == null && getProjectFile() != null
    }

    @Internal
    def getRootedProjectFile() {
        project.file(getProjectFile())
    }

    @Internal
    def getRootedSolutionFile() {
        project.file(getSolutionFile())
    }

    @Internal
    Map<String, ProjectFileParser> getProjects() {
        // Check if we should skip parsing for old MSBuild versions before attempting to parse
        // This prevents .NET SDK MSBuild from trying to parse old-style projects it can't handle
        def useOldMsbuild = version != null && (version.startsWith('14.') || version.startsWith('12.') || 
            version.startsWith('4.') || version == '14.0' || version == '12.0')
        
        if (useOldMsbuild) {
            logger.warn("Skipping project file parsing for old MSBuild version (${version}). " +
                "ProjectFileParser uses .NET SDK MSBuild which cannot parse old-style projects. " +
                "The build will proceed using Mono's MSBuild.")
            parseProject = false
            // Initialize allProjects as empty map to prevent NPE
            if (allProjects == null) {
                allProjects = [:]
            }
            return allProjects
        }
        
        // Try to resolve/parse, but catch ALL errors and check if they're old project format errors
        try {
            if (projectParsed == null && parseProject) {
                resolveProject()
            }
        } catch (OldProjectFormatException e) {
            // Old project format detected - skip parsing
            logger.warn("Old-style project format detected. Build will proceed using Mono's MSBuild.")
            parseProject = false
            if (allProjects == null) {
                allProjects = [:]
            }
            return allProjects
        } catch (Throwable e) {
            // Catch ALL exceptions/errors during parsing (including GradleException)
            // Get full error message including cause chain and stack trace
            def fullErrorMsg = getFullErrorMessage(e).toLowerCase()
            def stackTrace = getStackTrace(e).toLowerCase()
            def combinedError = "${fullErrorMsg} ${stackTrace}"
            
            // Check for old project format errors - be very lenient with matching
            // Check both message and stack trace as the error might be in either
            if (combinedError.contains('substringbyasciichars') || 
                combinedError.contains('invalid static method') || 
                combinedError.contains('invalidprojectfileexception') || 
                combinedError.contains('microsoft.build.exceptions') ||
                (combinedError.contains('failed to parse project') && combinedError.contains('exit code: 255'))) {
                logger.warn("Failed to parse project file (old-style project detected, .NET SDK MSBuild cannot parse it). " +
                    "Build will proceed using Mono's MSBuild.")
                parseProject = false
                if (allProjects == null) {
                    allProjects = [:]
                }
                return allProjects
            }
            // Re-throw if it's a different error
            throw e
        }
        
        allProjects
    }
    
    private String getStackTrace(Throwable e) {
        def sw = new StringWriter()
        def pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        return sw.toString()
    }

    private String getFullErrorMessage(Exception e) {
        def msg = new StringBuilder()
        msg.append(e.message ?: '')
        def cause = e.cause
        while (cause != null) {
            msg.append(' ').append(cause.message ?: '')
            cause = cause.cause
        }
        return msg.toString()
    }
    
    @Internal
    ProjectFileParser getMainProject() {
        if (resolveProject()) {
            projectParsed
        } else {
            logger.warn "Main project was resolved to null due to a parse error. The .sln file might be missing or incorrectly named."
            throw new GradleException("Failed to resolve main project. Make sure the name of the .sln file matches the one of the repository")
        }
    }

    def parseProjectFile(def file) {
        logger.info "Parsing file $file ..."
        if (!file.exists()) {
            throw new GradleException("Project/Solution file $file does not exist")
        }
        File tempDir = Files.createTempDirectory(temporaryDir.toPath(), 'ProjectFileParser').toFile()
        tempDir.deleteOnExit()

        this.class.getResourceAsStream('/META-INF/ProjectFileParser.zip').withCloseable  {
            ZipInputStream zis = new ZipInputStream(it)
            ZipEntry ze = zis.getNextEntry()
            while (ze != null) {
                String fileName = ze.getName()
                if (ze.isDirectory()) {
                    File subFolder = new File(tempDir, fileName)
                    subFolder.mkdir()
                    ze = zis.getNextEntry()
                    continue
                }
                File target = new File(tempDir, fileName)
                target.newOutputStream().leftShift(zis).close()
                ze = zis.getNextEntry()
            }
        }

        def parserDll = new File(tempDir, 'ProjectFileParser.dll')
        def parseOutputStream = new ByteArrayOutputStream()
        def errorOutputStream = new ByteArrayOutputStream()
        def parser = execOps.exec { exec ->
            exec.commandLine('dotnet', '--roll-forward', 'Major', parserDll)
            exec.args(file.toString(), JsonOutput.toJson(getInitProperties()).replace('"', '\''))
            exec.standardOutput = parseOutputStream
            exec.errorOutput = errorOutputStream
            // We want to be able to print the details of what actually failed, otherwise we won't have this info
            exec.ignoreExitValue = true
        }
        if (parser.exitValue != 0) {
            def errorOutput = errorOutputStream.toString()
            def stdOutput = parseOutputStream.toString()
            def combinedOutput = "${stdOutput} ${errorOutput}".toLowerCase()
            
            // Check if this is an old-style project that .NET SDK MSBuild can't parse
            // Strategy: If exit code is 255 and version is 14.0/12.0, treat as old project error
            // Also check error output for specific error strings
            def isOldVersion = version != null && version.toString().matches(/^(14|12)(\..*)?$/)
            def isOldVersionWith255 = (parser.exitValue == 255 && isOldVersion)
            
            // Check error output (case-insensitive)
            def hasSubstringError = combinedOutput.contains('substringbyasciichars')
            def hasInvalidMethod = combinedOutput.contains('invalid static method')
            def hasInvalidProject = combinedOutput.contains('invalidprojectfileexception')
            def hasMsBuildExceptions = combinedOutput.contains('microsoft.build.exceptions')
            
            def isOldProjectError = isOldVersionWith255 || hasSubstringError || hasInvalidMethod || hasInvalidProject || hasMsBuildExceptions
            
            if (isOldProjectError) {
                logger.warn("ProjectFileParser failed to parse old-style project (exit code: ${parser.exitValue}). " +
                    "This is expected when using .NET SDK MSBuild with old projects. " +
                    "Build will proceed using Mono's MSBuild.")
                throw new OldProjectFormatException("Old project format detected - .NET SDK MSBuild cannot parse it")
            }
            
            logger.error("ProjectFileParser failed with exit code: ${parser.exitValue}")
            logger.error("Standard output: ${stdOutput}")
            logger.error("Error output: ${errorOutput}")
            throw new GradleException("Failed to parse project, exit code: ${parser.exitValue}, output: '${stdOutput},' error: '${errorOutput}'")
        }

        def processOutput = parseOutputStream.toString()
        return new JsonSlurper().parseText(processOutput.substring(processOutput.indexOf('{')))
    }

    boolean resolveProject() {
        if (projectParsed == null && parseProject) {
            // For old MSBuild versions (14.0, 12.0, etc.), skip parsing as .NET SDK MSBuild can't parse them
            // The build will use Mono's MSBuild which can handle these projects
            def useOldMsbuild = version != null && (version.startsWith('14.') || version.startsWith('12.') || 
                version.startsWith('4.') || version == '14.0' || version == '12.0')
            
            if (useOldMsbuild) {
                logger.warn("Skipping project file parsing for old MSBuild version (${version}). " +
                    "ProjectFileParser uses .NET SDK MSBuild which cannot parse old-style projects. " +
                    "The build will proceed using Mono's MSBuild.")
                parseProject = false
                return false
            }
            
            if (isSolutionBuild()) {
                def rootSolutionFile = getRootedSolutionFile()
                try {
                    def result = parseProjectFile(rootSolutionFile)
                    allProjects = result.collectEntries { [it.key, new ProjectFileParser(msbuild: this, eval: it.value)] }
                    def projectName = getProjectName()
                    if (projectName == null || projectName.isEmpty()) {
                        parseProject = false
                    } else {
                        projectParsed = allProjects[projectName]
                        if (projectParsed == null) {
                            parseProject = false
                            logger.warn "Project ${projectName} not found in solution"
                        }
                    }
                } catch (OldProjectFormatException e) {
                    // Old project format - skip parsing
                    logger.warn("Old-style project format detected. Build will proceed using Mono's MSBuild.")
                    parseProject = false
                    if (allProjects == null) {
                        allProjects = [:]
                    }
                    return false
                } catch (GradleException e) {
                    // Check if this is an old project format error
                    def errorMsg = e.message?.toString() ?: ''
                    if (errorMsg.contains('SubstringByAsciiChars') || errorMsg.contains('Invalid static method') || 
                        errorMsg.contains('InvalidProjectFileException') || errorMsg.contains('Microsoft.Build.Exceptions')) {
                        logger.warn("Failed to parse old-style project file. Build will proceed using Mono's MSBuild.")
                        parseProject = false
                        if (allProjects == null) {
                            allProjects = [:]
                        }
                        return false
                    }
                    // Re-throw if it's a different error
                    throw e
                } catch (Exception e) {
                    // If parsing fails (e.g., old project format), log warning and continue
                    def errorMsg = e.message?.toString() ?: ''
                    if (errorMsg.contains('SubstringByAsciiChars') || errorMsg.contains('Invalid static method') || 
                        errorMsg.contains('InvalidProjectFileException')) {
                        logger.warn("Failed to parse old-style project file. Build will proceed using Mono's MSBuild.")
                    } else {
                        logger.warn("Failed to parse project file: ${e.message}. Build will proceed without parsing.")
                    }
                    parseProject = false
                    if (allProjects == null) {
                        allProjects = [:]
                    }
                    return false
                }
            } else if (isProjectBuild()) {
                def rootProjectFile = getRootedProjectFile()
                try {
                    def result = parseProjectFile(rootProjectFile)
                    allProjects = result.collectEntries {[it.key, new ProjectFileParser(msbuild: this, eval: it.value)]}
                    projectParsed = allProjects.values().first()
                     if (!projectParsed) {
                        logger.warn "Parsed project ${rootProjectFile} is null (not a solution / project build)"
                    }
                } catch (OldProjectFormatException e) {
                    // Old project format - skip parsing
                    logger.warn("Old-style project format detected. Build will proceed using Mono's MSBuild.")
                    parseProject = false
                    if (allProjects == null) {
                        allProjects = [:]
                    }
                    return false
                } catch (GradleException e) {
                    // Check if this is an old project format error
                    def errorMsg = e.message?.toString() ?: ''
                    if (errorMsg.contains('SubstringByAsciiChars') || errorMsg.contains('Invalid static method') || 
                        errorMsg.contains('InvalidProjectFileException') || errorMsg.contains('Microsoft.Build.Exceptions')) {
                        logger.warn("Failed to parse old-style project file. Build will proceed using Mono's MSBuild.")
                        parseProject = false
                        if (allProjects == null) {
                            allProjects = [:]
                        }
                        return false
                    }
                    // Re-throw if it's a different error
                    throw e
                } catch (Exception e) {
                    // If parsing fails (e.g., old project format), log warning and continue
                    def errorMsg = e.message?.toString() ?: ''
                    if (errorMsg.contains('SubstringByAsciiChars') || errorMsg.contains('Invalid static method') || 
                        errorMsg.contains('InvalidProjectFileException')) {
                        logger.warn("Failed to parse old-style project file. Build will proceed using Mono's MSBuild.")
                    } else {
                        logger.warn("Failed to parse project file: ${e.message}. Build will proceed without parsing.")
                    }
                    parseProject = false
                    if (allProjects == null) {
                        allProjects = [:]
                    }
                    return false
                }
            }
        }

        projectParsed != null
    }

    void setTarget(String s) {
        targets = [s]
    }

    @TaskAction
    def build() {
        // Use ExecOperations (injected via @Inject) for Gradle 8/9 compatibility
        // This replaces project.exec() which was deprecated/removed in Gradle 9
        def commandLineArgs = getCommandLineArgs()
        execOps.exec { exec ->
            exec.commandLine(commandLineArgs)
            exec.workingDir(project.projectDir)
        }
    }

    @Internal
    def getCommandLineArgs() {
        resolver.setupExecutable(this)

        // For dotnet msbuild, msbuildDir can be null (we use 'dotnet msbuild' directly)
        if (msbuildDir == null && executable != 'dotnet') {
            throw new GradleException("$executable not found")
        }
        
        def commandLineArgs
        if (executable == 'dotnet') {
            // Use dotnet msbuild directly
            commandLineArgs = resolver.executeDotNet(null).command()
        } else {
            commandLineArgs = resolver.executeDotNet(new File(msbuildDir, executable)).command()
        }

        commandLineArgs += '/nologo'

        if (isSolutionBuild()) {
            commandLineArgs += getRootedSolutionFile()
        } else if (isProjectBuild()) {
            commandLineArgs += getRootedProjectFile()
        }

        if (loggerAssembly) {
            commandLineArgs += '/l:' + loggerAssembly
        }
        if (targets && !targets.isEmpty()) {
            commandLineArgs += '/t:' + targets.join(';')
        }

        String verb = getMSVerbosity(verbosity)
        if (verb) {
            commandLineArgs += '/v:' + verb
        }

        def cmdParameters = getInitProperties()

        cmdParameters.each {
            if (it.value) {
                commandLineArgs += '/p:' + it.key + '=' + it.value
            }
        }

        def extMap = getExtensions()?.getExtraProperties()?.getProperties()
        if (extMap != null) {
            commandLineArgs += extMap.collect { k, v ->
                v ? "/$k:$v" : "/$k"
            }
        }

        commandLineArgs
    }

    String getMSVerbosity(String verbosity) {
        if (verbosity) return verbosity
        if (logger.debugEnabled) return 'detailed'
        if (logger.infoEnabled) return 'normal'
        return 'minimal' // 'quiet'
    }

    @Internal
    Map getInitProperties() {
        def cmdParameters = new HashMap<String, Object>()
        if (parameters != null) {
            cmdParameters.putAll(parameters)
        }
        cmdParameters.Project = getProjectName()
        cmdParameters.GenerateDocumentation = generateDoc
        cmdParameters.DebugType = debugType
        cmdParameters.Optimize = optimize
        cmdParameters.DebugSymbols = debugSymbols
        cmdParameters.OutputPath = destinationDir == null ? null : project.file(destinationDir)
        cmdParameters.IntermediateOutputPath = intermediateDir == null ? null : project.file(intermediateDir)
        cmdParameters.Configuration = configuration
        cmdParameters.Platform = platform
        if (defineConstants != null && !defineConstants.isEmpty()) {
            cmdParameters.DefineConstants = defineConstants.join(';')
        }
        def iter = cmdParameters.iterator()
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next()
            if (entry.value == null) {
                iter.remove()
            } else if (entry.value instanceof File) {
                entry.value = entry.value.path
            } else if (!entry.value instanceof String) {
                entry.value = entry.value.toString()
            }
        }
        ['OutDir', 'OutputPath', 'BaseIntermediateOutputPath', 'IntermediateOutputPath', 'PublishDir'].each {
            if (cmdParameters[it] && !cmdParameters[it].endsWith('\\')) {
                cmdParameters[it] += '\\'
            }
        }
        return cmdParameters
    }
}
