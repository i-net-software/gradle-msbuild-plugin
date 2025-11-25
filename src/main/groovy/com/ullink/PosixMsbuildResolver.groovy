package com.ullink

import org.gradle.api.GradleException

class PosixMsbuildResolver implements IExecutableResolver {

    String[] msbuild

    PosixMsbuildResolver(String version) {
        msbuild = locateMsBuild(version)
    }

    boolean msBuildFound() {
        return msbuild != null
    }

    @Override
    ProcessBuilder executeDotNet(File exe) {
        return new ProcessBuilder('mono', exe.toString())
    }

    void setupExecutable(Msbuild msbuild) {
        msbuild.executable = 'MSBuild.dll'
        if (msbuild.msbuildDir == null) {
            msbuild.msbuildDir = getMsBuildDir(locateMsBuild(msbuild.version))
        }
    }

    static List<String[]> locateMsBuilds() {
        List<String> msbuildRoots = []
        
        // Try to get Mono binary root directory, but handle gracefully if Mono is not installed
        try {
            msbuildRoots.add(XbuildResolver.getMonoBinaryRootDirectory())
        } catch (GradleException e) {
            // Mono not found - continue with OSX directories only
        }
        
        // Add OSX Mono root directories (may be empty if Mono is not installed)
        msbuildRoots.addAll(XbuildResolver.getOSXMonoRootDirectories())

        def existingMsBuilds = msbuildRoots
                .collectMany { ["$it/lib/mono", "$it/lib/mono/msbuild"] }
                .collectMany { XbuildResolver.getVersionDirectories(it) }
                .collectMany { [
                [new File(it[0], "MSBuild.dll"), it[1]],
                [new File(it[0], "bin/MSBuild.dll"), it[1]]
        ]}
        .findAll { it[0].exists() }

        return existingMsBuilds
    }

    static String[] locateMsBuild(String version = null) {
        def msbuilds = locateMsBuilds()
        if (msbuilds.isEmpty()) {
            return null
        }
        String[] msbuild
        if(version == null) {
            msbuild = msbuilds.first()
        }
        else {
            msbuild = msbuilds.find { (version == it[1]?.toString()) }
        }

        return msbuild
    }

    static String getMsBuildDir(String[] msbuild) {
        if (msbuild == null || msbuild.length == 0) {
            throw new GradleException("MSBuild not found. Mono SDK is required but not installed.")
        }
        return new File(msbuild.first()).getParent()
    }
}
