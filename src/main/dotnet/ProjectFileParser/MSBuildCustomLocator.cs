using Microsoft.Build.Locator;
using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace ProjectFileParser
{
    public static class MSBuildCustomLocator
    {
        public static void Register()
        {
            try
            {
                var versions = MSBuildLocator.QueryVisualStudioInstances().OrderBy(vsInstance => vsInstance.Version);
                if (versions.Any())
                {
                    var latestVsVersion = versions.Last();
                    MSBuildLocator.RegisterInstance(latestVsVersion);
                    Console.Error.WriteLine($"Registered latest VS Instance: {latestVsVersion.Name} - {latestVsVersion.Version} - {latestVsVersion.MSBuildPath} - {latestVsVersion.DiscoveryType} - {latestVsVersion.VisualStudioRootPath}");
                    return;
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"MSBuildLocator.QueryVisualStudioInstances() failed: {ex.Message}");
            }
            
            // No Visual Studio found - try to find .NET SDK MSBuild
            try
            {
                var dotnetSdkMsbuildPath = FindDotnetSdkMsbuild();
                if (dotnetSdkMsbuildPath != null && Directory.Exists(dotnetSdkMsbuildPath))
                {
                    // Create a VisualStudioInstance manually for .NET SDK
                    // Note: This is a workaround - MSBuildLocator might not support this directly
                    Console.Error.WriteLine($"Found .NET SDK MSBuild at: {dotnetSdkMsbuildPath}");
                    Console.Error.WriteLine("Note: MSBuildLocator registration may not be needed for .NET SDK MSBuild");
                    // The assemblies should be available via NuGet package references now
                }
                else
                {
                    Console.Error.WriteLine("No Visual Studio instances found and could not locate .NET SDK MSBuild.");
                    Console.Error.WriteLine("Microsoft.Build assemblies should be available via NuGet package references.");
                }
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"Error finding .NET SDK MSBuild: {ex.Message}");
                Console.Error.WriteLine("Microsoft.Build assemblies should be available via NuGet package references.");
            }
        }
        
        private static string FindDotnetSdkMsbuild()
        {
            try
            {
                // Try to get dotnet SDK path from environment or common locations
                var dotnetRoot = Environment.GetEnvironmentVariable("DOTNET_ROOT");
                if (string.IsNullOrEmpty(dotnetRoot))
                {
                    // Try common locations
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.Linux))
                    {
                        var commonPaths = new[] { "/usr/share/dotnet", "/usr/local/share/dotnet", 
                            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".dotnet") };
                        foreach (var path in commonPaths)
                        {
                            if (Directory.Exists(path))
                            {
                                dotnetRoot = path;
                                break;
                            }
                        }
                    }
                }
                
                if (!string.IsNullOrEmpty(dotnetRoot))
                {
                    var sdkPath = Path.Combine(dotnetRoot, "sdk");
                    if (Directory.Exists(sdkPath))
                    {
                        // Find the highest SDK version
                        var sdkVersions = Directory.GetDirectories(sdkPath)
                            .Where(d => System.Text.RegularExpressions.Regex.IsMatch(Path.GetFileName(d), @"^\d+\.\d+\.\d+"))
                            .OrderByDescending(d => new Version(Path.GetFileName(d).Split('-')[0]))
                            .FirstOrDefault();
                            
                        if (sdkVersions != null)
                        {
                            var msbuildPath = Path.Combine(sdkVersions, "MSBuild", "Current", "Bin");
                            if (Directory.Exists(msbuildPath))
                            {
                                return msbuildPath;
                            }
                        }
                    }
                }
            }
            catch
            {
                // Ignore errors
            }
            return null;
        }
    }
}