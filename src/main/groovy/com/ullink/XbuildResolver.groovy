package com.ullink

import org.gradle.api.GradleException

class XbuildResolver implements IExecutableResolver {
    
    private boolean usingDotnetMsbuild = false

    @Override
    ProcessBuilder executeDotNet(File exe) {
        if (usingDotnetMsbuild) {
            // Use dotnet msbuild command (exe parameter is ignored, we use dotnet msbuild directly)
            return new ProcessBuilder('dotnet', 'msbuild')
        } else {
            // Use mono for Mono's xbuild/MSBuild
            return new ProcessBuilder('mono', exe.toString())
        }
    }

    void setupExecutable(Msbuild msbuild) {
        // For old MSBuild versions (14.0, 12.0, etc.), ALWAYS use Mono's MSBuild/xbuild
        // .NET SDK MSBuild cannot build .NET Framework projects (missing reference assemblies)
        def versionStr = msbuild.version?.toString() ?: ''
        def useMonoForOldProjects = versionStr && 
            (versionStr.startsWith('14.') || versionStr.startsWith('12.') || 
             versionStr.startsWith('4.') || versionStr == '14.0' || versionStr == '12.0')
        
        msbuild.logger.debug("MSBuild version check: version='${versionStr}', useMonoForOldProjects=${useMonoForOldProjects}")
        
        // Also check if project file indicates .NET Framework (ToolsVersion="14.0" or TargetFramework contains "net4")
        def isDotNetFrameworkProject = false
        if (!useMonoForOldProjects) {
            try {
                // Check solution file first
                def solutionFile = msbuild.isSolutionBuild() ? msbuild.getRootedSolutionFile() : null
                def projectFile = msbuild.isProjectBuild() ? msbuild.getRootedProjectFile() : null
                
                // For solutions, check the first project file referenced
                if (solutionFile && solutionFile.exists()) {
                    def solutionContent = solutionFile.text
                    // Check solution file for Visual Studio 14 (indicates .NET Framework project)
                    if (solutionContent.contains('VisualStudioVersion = 14.') || 
                        solutionContent.contains('# Visual Studio 14') ||
                        solutionContent.contains('ToolsVersion="14.0') || 
                        solutionContent.contains("ToolsVersion='14.0")) {
                        isDotNetFrameworkProject = true
                        msbuild.logger.info("Detected .NET Framework project from solution file (Visual Studio 14/ToolsVersion=14.0) - Mono's MSBuild required")
                    } else {
                        // Try to find and check a project file from the solution
                        // Pattern matches: Project(...) = "Name", "path\file.csproj", "{guid}"
                        def projectPattern = ~/Project\([^)]+\)\s*=\s*"[^"]+",\s*"([^"]+\.csproj)"/
                        def matcher = projectPattern.matcher(solutionContent)
                        if (matcher.find()) {
                            // Handle both Windows (\) and Unix (/) path separators
                            def projectRelativePath = matcher.group(1).replace('\\', File.separator)
                            def projectPath = new File(solutionFile.parentFile, projectRelativePath)
                            if (projectPath.exists()) {
                                def projectContent = projectPath.text
                                if (projectContent.contains('ToolsVersion="14.0') || 
                                    projectContent.contains("ToolsVersion='14.0") ||
                                    projectContent.contains('TargetFrameworkVersion') || 
                                    (projectContent.contains('TargetFramework') && (projectContent.contains('net4') || projectContent.contains('netframework')))) {
                                    isDotNetFrameworkProject = true
                                    msbuild.logger.info("Detected .NET Framework project from project file (${projectPath.name}) - Mono's MSBuild required")
                                }
                            } else {
                                msbuild.logger.debug("Project file not found: ${projectPath.absolutePath} (resolved from: ${matcher.group(1)})")
                            }
                        }
                    }
                } else if (projectFile && projectFile.exists()) {
                    def content = projectFile.text
                    // Check for .NET Framework indicators
                    if (content.contains('ToolsVersion="14.0') || 
                        content.contains("ToolsVersion='14.0") ||
                        content.contains('TargetFrameworkVersion') ||
                        (content.contains('TargetFramework') && (content.contains('net4') || content.contains('netframework')))) {
                        isDotNetFrameworkProject = true
                        msbuild.logger.info("Detected .NET Framework project from project file - Mono's MSBuild required")
                    }
                }
            } catch (Exception e) {
                msbuild.logger.debug("Could not check project file for .NET Framework: ${e.message}")
            }
        }
        
        if (useMonoForOldProjects || isDotNetFrameworkProject) {
            msbuild.logger.info("Old MSBuild version (${msbuild.version}) or .NET Framework project detected - Mono's MSBuild/xbuild required (dotnet msbuild cannot build .NET Framework projects)")
            // Try Mono's MSBuild first
            def msBuildResolver = new PosixMsbuildResolver(msbuild.version)
            if(msBuildResolver.msBuildFound()) {
                msBuildResolver.setupExecutable(msbuild)
                return
            }
            // Fall back to Mono's xbuild
            try {
                msbuild.executable = 'xbuild.exe'
                if (msbuild.msbuildDir == null) {
                    msbuild.msbuildDir = getXBuildDir(msbuild)
                }
                return
            } catch (GradleException e) {
                // Mono/xbuild not found - fail with clear error message
                throw new GradleException(
                    "Cannot build .NET Framework project. " +
                    "Mono's MSBuild or xbuild is required for .NET Framework projects, but was not found. " +
                    "Please install Mono SDK or set msbuildDir to point to Mono's MSBuild installation. " +
                    "dotnet msbuild cannot build .NET Framework projects (missing reference assemblies). " +
                    "Error: ${e.message}", e)
            }
        }
        
        // For modern projects, try dotnet msbuild first
        def dotnetPath = findDotnetPath()
        if (dotnetPath) {
            msbuild.executable = 'dotnet'
            msbuild.msbuildDir = null // Not needed for dotnet msbuild
            usingDotnetMsbuild = true
            msbuild.logger.info("Auto-detected dotnet SDK, using 'dotnet msbuild'")
            return
        }
        
        // Fall back to Mono's MSBuild
        def msBuildResolver = new PosixMsbuildResolver(msbuild.version)
        if(msBuildResolver.msBuildFound()) {
            msBuildResolver.setupExecutable(msbuild)
        }
        else {
            // Last resort: try Mono's xbuild
            msbuild.executable = 'xbuild.exe'
            if (msbuild.msbuildDir == null) {
                msbuild.msbuildDir = getXBuildDir(msbuild)
            }
        }
    }
    
    /**
     * Find the dotnet executable path
     */
    private String findDotnetPath() {
        try {
            def process = ['which', 'dotnet'].execute()
            process.waitFor()
            if (process.exitValue() == 0) {
                def path = process.in.text?.trim()
                // Verify dotnet is actually executable and can run msbuild
                if (path) {
                    try {
                        def testProcess = [path, 'msbuild', '-version'].execute()
                        testProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                        if (testProcess.exitValue() == 0 || testProcess.exitValue() == 1) {
                            // Exit code 0 or 1 is OK (1 might be "no project file" which is fine)
                            return path
                        }
                    } catch (Exception e) {
                        // dotnet msbuild not available
                    }
                }
            }
        } catch (Exception e) {
            // dotnet not found
        }
        return null
    }

    public static String getXBuildDir(Msbuild msbuild) {
        if (msbuild.version != null)
            msbuild.logger.info("MSBuild version explicitly set to: '${msbuild.version}'")

        List<String> xbuildRoots = [getMonoBinaryRootDirectory()] + getOSXMonoRootDirectories()
        /*
            we can encounter the following scenario:
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/4.5
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/4.5-api
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/14.0
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/12.0
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/4.5
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/xbuild/12.0

            so just make sure we're sorting (additionally) by the last path segment's version, which would yield:

                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/14.0
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/xbuild/12.0
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/xbuild/12.0
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/4.5
                /Library/Frameworks/Mono.framework/Versions/4.0.0/lib/mono/4.5-api
                /Library/Frameworks/Mono.framework/Versions/3.12.0/lib/mono/4.5
         */
        def existingXBuilds = xbuildRoots
                .collectMany { ["$it/lib/mono", "$it/lib/mono/xbuild"] }
                .collectMany { getVersionDirectories(it) }
                .collectMany { [
                [new File(it[0], "xbuild.exe"), it[1]],
                [new File(it[0], "bin/xbuild.exe"), it[1]]
        ]}
        .findAll { it[0].exists() }

        def foundXBuild = existingXBuilds.find { msbuild.version == null || msbuild.version.equals("${it[1][0]}.${it[1][1]}".toString()) }
        if (foundXBuild != null) {
            File file = foundXBuild[0]
            msbuild.logger.info("Resolved xbuild to: ${file.absolutePath}")
            return file.getParent()
        }

        throw new GradleException("Cannot find an xbuild binary. Is mono SDK installed? " +
                "(Existing binaries: ${existingXBuilds.collect{it[0]}})")
    }

    private static List<String> getOSXMonoRootDirectories() {
        getVersionDirectories('/Library/Frameworks/Mono.framework/Versions/').collect { it[0] }
    }

    private static List<String[]> getVersionDirectories(String path) {
        File file = new File(path)
        if (!file.exists()) {
            return []
        }
        return file.listFiles()
                .findAll { it.isDirectory() }
                .collect { [it.absolutePath, parseVersion(it.name)] }
                .findAll { it[1] != null }
                .sort { a, b -> compareVersions(b[1], a[1]) }
    }

    // Simple version parsing to replace org.gradle.util.VersionNumber (removed in Gradle 7+)
    private static List<Integer> parseVersion(String versionString) {
        try {
            def parts = versionString.split('\\.')
            def major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0
            def minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0
            def micro = parts.length > 2 ? Integer.parseInt(parts[2]) : 0
            return [major, minor, micro]
        } catch (NumberFormatException e) {
            return null // Invalid version string
        }
    }

    // Compare two version arrays [major, minor, micro]
    private static int compareVersions(List<Integer> v1, List<Integer> v2) {
        if (v1 == null && v2 == null) return 0
        if (v1 == null) return -1
        if (v2 == null) return 1
        
        for (int i = 0; i < Math.max(v1.size(), v2.size()); i++) {
            def part1 = i < v1.size() ? v1[i] : 0
            def part2 = i < v2.size() ? v2[i] : 0
            def cmp = part1 <=> part2
            if (cmp != 0) return cmp
        }
        return 0
    }


    private static String getMonoBinaryRootDirectory() {
        def which = "which mono".execute()
        which.waitFor()
        def monoRoot = which.in.text
        if (monoRoot == null || monoRoot.isEmpty())
            throw new GradleException("Can't get mono location. Mono default installation prefix is usually /usr/lib/")
        monoRoot - "/bin/mono\n"
    }
}
