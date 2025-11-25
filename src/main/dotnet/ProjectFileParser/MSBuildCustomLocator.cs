using Microsoft.Build.Locator;
using System;
using System.Linq;

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
                }
                else
                {
                    // No Visual Studio found (e.g., on Linux with dotnet SDK only)
                    // This is OK - dotnet msbuild doesn't require MSBuildLocator
                    Console.Error.WriteLine("No Visual Studio instances found. Using dotnet msbuild (no registration needed).");
                }
            }
            catch (Exception ex)
            {
                // On Linux or when VS is not installed, this is expected
                // dotnet msbuild works without MSBuildLocator registration
                Console.Error.WriteLine("MSBuildLocator cannot detect VS location (this is OK on Linux with dotnet SDK)");
                Console.Error.WriteLine("Error was: {0}", ex);
            }
        }
    }
}