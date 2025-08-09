#Requires -Version 5.1
<#
.SYNOPSIS
    Converts a directory of Markdown files into a styled HTML documentation site with a navigation menu.

.DESCRIPTION
    This script automates the process of generating a simple, clean HTML documentation website from a collection of Markdown (.md) files.
    It uses Pandoc to perform the conversion and supports Mermaid.js for diagrams.

    Features:
    - Converts all .md files in a source directory to .html files in an output directory.
    - Automatically generates a navigation sidebar from the files.
    - Supports custom navigation via a 'menu.json' file.
    - Allows customization of project title and primary theme color.
    - Can optionally create a sample 'menu.json' file for you.
    - Automatically opens the generated documentation in the default web browser.

.PARAMETER SourceDir
    The directory containing the Markdown (.md) files to be converted.
    Defaults to the current directory.

.PARAMETER OutputDir
    The directory where the generated HTML files will be saved.
    Defaults to a subfolder named 'html' in the current directory.

.PARAMETER ProjectTitle
    The title for the documentation project, which will appear in the HTML page titles.
    Use quotes for titles with spaces. Defaults to "Documentation".

.PARAMETER MenuConfig
    Controls the navigation menu generation.
    - 'auto': (Default) Automatically generates the menu from the .md files.
    - 'none': No navigation menu is generated, only a link to 'index.html'.
    - [path-to-file.json]: Path to a custom 'menu.json' file.

.PARAMETER PrimaryColor
    The primary color (in hex format, e.g., '#0066cc') used for headings and links in the HTML output.
    Defaults to '#0066cc'.

.PARAMETER OpenInBrowser
    A switch parameter that, if specified as $true (the default), opens the generated index.html in the default web browser upon completion.
    To disable, use the syntax: -OpenInBrowser:$false

.PARAMETER Help
    Displays this help message.

.PARAMETER CreateMenuJson
    A switch parameter that creates a sample 'menu.json' file based on the Markdown files found in the SourceDir and then exits.
    Use this to get a template that you can then customize.

.PARAMETER MenuJsonPath
    The path (including filename) where the sample menu.json file should be created when using -CreateMenuJson.
    Defaults to 'menu.json' in the current directory.

.EXAMPLE
    .\md2html.ps1
    Converts Markdown files in the current directory to HTML in './html' with default settings.

.EXAMPLE
    .\md2html.ps1 -SourceDir .\docs -OutputDir .\public
    Specifies source and output directories.

.EXAMPLE
    .\md2html.ps1 -ProjectTitle "My Awesome Project" -PrimaryColor "#d9534f"
    Customizes the project title and theme color.

.EXAMPLE
    .\md2html.ps1 -OpenInBrowser:$false
    Generates the documentation but does not open it in a browser.

.EXAMPLE
    .\md2html.ps1 -CreateMenuJson -MenuJsonPath "custom-menu.json"
    Creates a sample 'custom-menu.json' file and exits.

.EXAMPLE
    .\md2html.ps1 -MenuConfig .\custom-menu.json
    Uses a custom menu configuration file for navigation.
#>

param(
    [Parameter(Position=0)]
    [string]$SourceDir = (Get-Location).Path,

    [Parameter()]
    [string]$OutputDir = (Join-Path (Get-Location).Path "html"),

    [Parameter()]
    [string]$ProjectTitle = "Documentation",

    [Parameter()]
    [string]$MenuConfig = "auto",

    [Parameter()]
    [string]$PrimaryColor = "#0066cc",

    [Parameter()]
    [switch]$OpenInBrowser = $true,

    [Parameter(HelpMessage="Display this help message.")]
    [Alias("?")]
    [switch]$Help,

    [Parameter()]
    [switch]$CreateMenuJson = $false,

    [Parameter()]
    [string]$MenuJsonPath = (Join-Path $PSScriptRoot "menu.json")
)

# Manually check for help flags passed as arguments
# This is needed because PowerShell doesn't recognize --help as a switch
if ($args -contains "--help" -or $args -contains "-help" -or $args -contains "/?" -or $args -contains "/h" -or $SourceDir -eq "--help" -or $SourceDir -eq "-help") {
    $Help = $true
}

function Show-Help {
    Write-Host "md2html - Markdown to HTML Documentation Generator" -ForegroundColor Green
    Write-Host "=================================================" -ForegroundColor Green
    Write-Host
    Write-Host "DESCRIPTION:" -ForegroundColor Yellow
    Write-Host "  Converts Markdown files to HTML with Mermaid diagram support."
    Write-Host "  REQUIRES: Pandoc (https://pandoc.org/installing.html)"
    Write-Host
    Write-Host "USAGE:" -ForegroundColor Yellow
    Write-Host "  .\md2html.ps1 [options]"
    Write-Host "  .\md2html.ps1 -Help"
    Write-Host "  .\md2html.ps1 -SourceDir [path] -OutputDir [path] -ProjectTitle [title] ..."
    Write-Host "  Note: Always use parameter names (-ParameterName) to avoid confusion."
    Write-Host
    Write-Host "PARAMETERS:" -ForegroundColor Yellow
    Write-Host "  -SourceDir      Directory containing Markdown files (default: current directory)"
    Write-Host "  -OutputDir      Output directory for HTML files (default: ./html)"
    Write-Host "  -ProjectTitle   Title of the documentation project; MUST use quotes for multiple words (default: 'Documentation')"
    Write-Host "  -MenuConfig     'auto', 'none', or path to a menu.json file (default: 'auto')"
    Write-Host "  -PrimaryColor   Primary color for headings and links (default: '#0066cc')"
    Write-Host "  -OpenInBrowser  Open the generated documentation in browser (default: true)"
    Write-Host "  -CreateMenuJson Create a sample menu.json file from Markdown files (default: false)"
    Write-Host "  -MenuJsonPath   Path for the generated menu.json file (default: 'menu.json')"
    Write-Host "  -Help           Display this help message (Alias: --help, -h, /?, /h)"
    Write-Host
    Write-Host "EXAMPLES:" -ForegroundColor Yellow
    Write-Host "  # Basic usage - convert Markdown files in current directory:"
    Write-Host "  .\md2html.ps1"
    Write-Host
    Write-Host "  # Specify source and output directories:"
    Write-Host "  .\md2html.ps1 -SourceDir .\docs -OutputDir .\public"
    Write-Host
    Write-Host "  # Customize project title and theme color:"
    Write-Host "  .\md2html.ps1 -ProjectTitle 'My Documentation Project' -PrimaryColor '#cc0000'"
    Write-Host
    Write-Host "  # Minimal navigation and no browser opening:"
    Write-Host "  .\md2html.ps1 -OpenInBrowser:`$false"
    Write-Host "  # Note: For switch parameters, use colon syntax: -OpenInBrowser:`$false"
    Write-Host
    Write-Host "  # Create a sample menu.json file based on Markdown files:"
    Write-Host "  .\md2html.ps1 -CreateMenuJson -MenuJsonPath custom-menu.json"
    Write-Host
    Write-Host "  # Use a custom menu configuration file:"
    Write-Host "  .\md2html.ps1 -MenuConfig .\menu.json"
}

# Function to extract title and other info from Markdown files
function Get-MarkdownInfo {
    param(
        [string]$FilePath
    )

    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($FilePath)
    $title = ""

    # Try to extract title from the first heading in the file
    try {
        $content = Get-Content -Path $FilePath -TotalCount 10 -ErrorAction Stop
        foreach ($line in $content) {
            if ($line -match '^#\s+(.+)') {
                $title = $matches[1].Trim()
                break
            }
        }
    }
    catch {
        # File might be empty or unreadable, proceed with filename
    }


    # If no title found, use filename with formatting
    if ([string]::IsNullOrEmpty($title)) {
        $title = $baseName -replace '^\d+[\s._-]*', '' -replace '[\s_-]+', ' '
        $title = (Get-Culture).TextInfo.ToTitleCase($title.ToLower())
    }

    return @{
        BaseName = $baseName
        Title    = $title
    }
}

# Function to generate navigation menu based on Markdown files
function New-NavigationMenu {
    param(
        [string]$SourceDir,
        [string]$MenuConfig
    )

    # Default menu item for Home/Index
    $menuItems = @("<li><a href='index.html'>Home</a></li>")

    if ($MenuConfig -eq "none") {
        # No menu items except Home
        return $menuItems -join "`n"
    }
    elseif ($MenuConfig -eq "auto" -or [string]::IsNullOrEmpty($MenuConfig)) {
        # Add README.md if it exists and index.md does not
        $indexPath = Join-Path $SourceDir "index.md"
        $readmePath = Join-Path $SourceDir "README.md"
        if (-not (Test-Path $indexPath) -and (Test-Path $readmePath)) {
            $menuItems += "<li><a href='README.html'>README</a></li>"
        }

        # Auto-generate menu from markdown files, excluding index.md and README.md
        $mdFiles = Get-ChildItem -Path $SourceDir -Filter "*.md" |
            Where-Object { $_.Name -ne "index.md" -and $_.Name -ne "README.md" } |
            Sort-Object Name
        foreach ($file in $mdFiles) {
            $mdInfo = Get-MarkdownInfo -FilePath $file.FullName
            $menuItems += "<li><a href='$($mdInfo.BaseName).html'>$($mdInfo.Title)</a></li>"
        }
    }
    elseif (Test-Path $MenuConfig) {
        # Load menu from JSON file
        try {
            Write-Host "Attempting to load menu from JSON file: $MenuConfig" -ForegroundColor Cyan
            $jsonContent = Get-Content -Path $MenuConfig -Raw
            Write-Host "JSON Content read: $($jsonContent.Substring(0, [System.Math]::Min(200, $jsonContent.Length))) (truncated)" -ForegroundColor DarkCyan
            $customMenu = $jsonContent | ConvertFrom-Json
            Write-Host "Custom menu loaded. Number of items: $($customMenu.Count)" -ForegroundColor Cyan
            # Clear default home item, as it should be in the json
            $menuItems = @()
            foreach ($item in $customMenu) {
                $menuItems += "<li><a href='$($item.url)'>$($item.title)</a></li>"
            }
        }
        catch {
            Write-Host "Error loading custom menu: $_" -ForegroundColor Red
            # Fall back to auto-generation
            return (New-NavigationMenu -SourceDir $SourceDir -MenuConfig "auto")
        }
    }

    return $menuItems -join "`n"
}

# Function to create a menu.json file from Markdown files
function New-MenuJsonFile {
    param(
        [string]$SourceDir,
        [string]$OutputPath
    )
    Write-Host "Creating sample menu JSON file at $OutputPath..." -ForegroundColor Cyan

    $menuItems = @()

    # Add index.md if it exists
    $indexPath = Join-Path $SourceDir "index.md"
    if (Test-Path $indexPath) {
        $menuItems += @{
            title = "Home"
            url   = "index.html"
        }
    }

    # Add README.md if it exists and is not index.md
    $readmePath = Join-Path $SourceDir "README.md"
    if ((Test-Path $readmePath) -and (-not (Test-Path $indexPath))) {
        $menuItems += @{
            title = "README"
            url   = "README.html"
        }
    }

    # Add other markdown files, excluding index.md and README.md
    $mdFiles = Get-ChildItem -Path $SourceDir -Filter "*.md" |
        Where-Object { $_.Name -ne "index.md" -and $_.Name -ne "README.md" } |
        Sort-Object Name

    foreach ($file in $mdFiles) {
        $mdInfo = Get-MarkdownInfo -FilePath $file.FullName
        $menuItems += @{
            title = $mdInfo.Title
            url   = "$($mdInfo.BaseName).html"
        }
    }

    $menuJson = ConvertTo-Json $menuItems -Depth 3
    $menuJson | Out-File -FilePath $OutputPath -Encoding UTF8

    Write-Host "Sample menu JSON file created successfully at $OutputPath" -ForegroundColor Green
    Write-Host "You can now use this file with the -MenuConfig parameter:" -ForegroundColor Yellow
    Write-Host "  .\md2html.ps1 -MenuConfig $OutputPath" -ForegroundColor Yellow
}

# Function to check if Pandoc is installed
function Test-PandocInstallation {
    $pandocExists = $null
    try {
        $pandocExists = Get-Command pandoc -ErrorAction Stop
        Write-Host "Pandoc detected: $($pandocExists.Version)" -ForegroundColor Green
        return $true
    }
    catch {
        Write-Host "ERROR: Pandoc is not installed or not in PATH!" -ForegroundColor Red
        Write-Host "This script requires Pandoc to convert Markdown to HTML." -ForegroundColor Yellow
        Write-Host
        Write-Host "Please install Pandoc from: https://pandoc.org/installing.html" -ForegroundColor Cyan
        Write-Host "After installation, close and reopen your terminal/console." -ForegroundColor Cyan
        Write-Host
        return $false
    }
}

# Function to check if Markdown files exist in the source directory
function Test-MarkdownFilesExist {
    param(
        [string]$SourceDir
    )
    # Validate SourceDir exists
    if (-not (Test-Path -Path $SourceDir -PathType Container)) {
        Write-Host "ERROR: Source directory '$SourceDir' does not exist." -ForegroundColor Red
        return $false
    }
    
    $mdFiles = Get-ChildItem -Path $SourceDir -Filter "*.md" -ErrorAction SilentlyContinue
    if ($null -eq $mdFiles -or $mdFiles.Count -eq 0) {
        Write-Host "ERROR: No Markdown (.md) files found in $SourceDir" -ForegroundColor Red
        Write-Host "Please check the source directory path and ensure it contains Markdown files." -ForegroundColor Yellow
        Write-Host
        Write-Host "Usage examples:" -ForegroundColor Cyan
        Write-Host "  .\md2html.ps1 -SourceDir .\path\with\markdown\files"
        Write-Host "  .\md2html.ps1 -Help # For more information"
        return $false
    }

    Write-Host "Found $($mdFiles.Count) Markdown files in $SourceDir" -ForegroundColor Green
    return $true
}


# --- Main Script Execution ---

if ($Help) {
    Show-Help
    exit 0
}

Write-Host "$ProjectTitle Generator" -ForegroundColor Green
Write-Host "==============================" -ForegroundColor Green

# Check if Pandoc is installed before proceeding
if (-not (Test-PandocInstallation)) {
    exit 1
}

# Check if Markdown files exist in the source directory
if (-not (Test-MarkdownFilesExist -SourceDir $SourceDir)) {
    exit 1
}

# Resolve full paths
$SourceDir = [System.IO.Path]::GetFullPath($SourceDir)
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$MenuJsonPath = [System.IO.Path]::GetFullPath($MenuJsonPath)
# Resolve MenuConfig path if it's a file path
if ($MenuConfig -ne "auto" -and $MenuConfig -ne "none") {
    # If MenuConfig is a relative path, resolve it relative to the script's directory
    if (-not [System.IO.Path]::IsPathRooted($MenuConfig)) {
        $MenuConfig = Join-Path $PSScriptRoot $MenuConfig
    }
    # Ensure it's a full path
    $MenuConfig = [System.IO.Path]::GetFullPath($MenuConfig)
}

# If the goal is to create a menu JSON file, do that and exit
if ($CreateMenuJson) {
    New-MenuJsonFile -SourceDir $SourceDir -OutputPath $MenuJsonPath
    exit 0
}

# --- Setup Environment ---
Write-Host "Setting up environment for HTML generation..." -ForegroundColor Cyan

# Create the output directory if it doesn't exist
if (-not (Test-Path -Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
    Write-Host "Created output directory: $OutputDir" -ForegroundColor Green
}

# Get the list of markdown files to process
$MarkdownFiles = Get-ChildItem -Path $SourceDir -Filter "*.md"

# Define HTML template as a here-string
$template = @'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$title$</title>
    <style>
        body { font-family: 'Segoe UI', Arial, sans-serif; font-size: 16px; line-height: 1.6; margin: 0; padding: 0; color: #333; }
        .container { display: flex; width: 100%; }
        .sidebar { width: 250px; padding: 20px; background: #f8f9fa; border-right: 1px solid #e0e0e0; position: fixed; height: 100vh; overflow-y: auto; left: 0; top: 0; box-sizing: border-box; }
        .sidebar h2 { font-size: 22px; margin-top: 0; color: #333; }
        .sidebar ul { padding-left: 0; list-style: none; }
        .sidebar li { margin-bottom: 8px; }
        .sidebar a { display: block; padding: 4px 8px; text-decoration: none; border-radius: 4px; }
        .sidebar a:hover { background-color: #e9ecef; }
        .content { margin-left: 290px; padding: 20px 40px; max-width: 800px; box-sizing: border-box; }
        code { background-color: #f5f5f5; padding: 2px 4px; border-radius: 3px; font-size: 14px; font-family: Consolas, Monaco, 'Andale Mono', monospace; }
        pre { background-color: #f5f5f5; padding: 15px; border-radius: 5px; overflow-x: auto; font-size: 14px; }
        img { max-width: 100%; height: auto; }
        table { border-collapse: collapse; width: 100%; margin: 20px 0; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background-color: #f2f2f2; }
        h1, h2, h3, h4, h5, h6 { color: PRIMARYCOLOR; }
        h1 { font-size: 28px; border-bottom: 2px solid #eee; padding-bottom: 10px; }
        h2 { font-size: 24px; border-bottom: 1px solid #eee; padding-bottom: 8px; }
        h3 { font-size: 20px; }
        a { color: PRIMARYCOLOR; text-decoration: none; }
        a:hover { text-decoration: underline; }
        @media (max-width: 900px) {
            .container { flex-direction: column; }
            .sidebar { position: static; width: 100%; height: auto; border-right: none; border-bottom: 1px solid #e0e0e0; margin-bottom: 20px; }
            .content { margin-left: 0; width: 100%; padding: 20px; }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="sidebar">
            <h2>$ProjectTitle$</h2>
            <ul>
                NAVIGATION
            </ul>
        </div>
        <div class="content">
            $body$
        </div>
    </div>
    <script src="https://cdn.jsdelivr.net/npm/mermaid@10.7.0/dist/mermaid.min.js"></script>
    <script>
        document.addEventListener("DOMContentLoaded", function() {
            mermaid.initialize({ 
            startOnLoad: true,
            theme: 'forest',
            flowchart: {
                useMaxWidth: true,
                htmlLabels: true
            },
            sequence: {
                useMaxWidth: true,
                htmlLabels: true
            },
            er: {
                useMaxWidth: true,
                htmlLabels: true
            }
            });
        });
    </script>
</body>
</html>
'@

# Create a temporary directory for support files
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("md2html_" + [System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
Write-Host "Created temporary directory for support files: $tempDir" -ForegroundColor DarkCyan

# Generate the navigation menu
$navigationMenu = New-NavigationMenu -SourceDir $SourceDir -MenuConfig $MenuConfig

# Prepare the template by injecting dynamic values
$processedTemplate = $template.Replace('PRIMARYCOLOR', $PrimaryColor).Replace('NAVIGATION', $navigationMenu)
$templatePath = Join-Path $tempDir "template.html"
$processedTemplate | Out-File -FilePath $templatePath -Encoding UTF8

# Define Mermaid Lua filter for Pandoc
# Create mermaid-filter.lua in the temporary directory
Write-Host "Creating mermaid-filter.lua in temporary directory..." -ForegroundColor Cyan
$mermaidFilterContent = @'
function CodeBlock(block)
    if block.classes[1] == "mermaid" then
        return pandoc.RawBlock('html', string.format("<div class=\"mermaid\">%s</div>", block.text))
    end
    return block
end

-- Function to fix links by changing .md extensions to .html
function Link(el)
    local target = el.target
    if target:match("%.md$") then
        el.target = target:gsub("%.md$", ".html")
    end
    return el
end
'@

# Copy the mermaid-filter.lua file to the temporary directory
$filterPath = Join-Path $tempDir "mermaid-filter.lua"
$mermaidFilterContent | Out-File -FilePath $filterPath -Encoding UTF8

# --- Process Files ---
Write-Host "Starting conversion process..." -ForegroundColor Cyan

foreach ($file in $MarkdownFiles) {
    $baseName = $file.BaseName
    $outputFile = Join-Path $OutputDir "$baseName.html"
    
    # Get a proper title for the page header
    $mdInfo = Get-MarkdownInfo -FilePath $file.FullName
    $fileTitle = $mdInfo.Title

    Write-Host "Converting $($file.Name) to HTML..." -ForegroundColor Cyan

    # Run pandoc command
    $pandocArgs = @(
        $file.FullName,
        "-o", $outputFile,
        "-s", # standalone
        "--metadata", "title=$ProjectTitle - $fileTitle",
        "--template=$templatePath",
        "--lua-filter=$filterPath",
        "--from", "markdown+smart" # use smart typography
    )
    
    & pandoc $pandocArgs
    
    # Check for errors
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Error converting $($file.Name) to HTML" -ForegroundColor Red
    }
}

# --- Cleanup and Final Steps ---

# Remove the temporary directory
try {
    Remove-Item -Path $tempDir -Recurse -Force -ErrorAction Stop
    Write-Host "Temporary files cleaned up successfully" -ForegroundColor DarkGray
}
catch {
    Write-Host "Warning: Could not remove temporary directory: $tempDir" -ForegroundColor Yellow
}

Write-Host
Write-Host "[SUCCESS] Documentation generation completed successfully!" -ForegroundColor Green
Write-Host "[SUCCESS] HTML files are in $OutputDir" -ForegroundColor Green
Write-Host

# Open the main file in the default browser if not disabled
if ($OpenInBrowser) {
    $indexPath = Join-Path $OutputDir "index.html"
    if (-not (Test-Path $indexPath)) {
        $indexPath = Join-Path $OutputDir "README.html" # Fallback to README
    }

    if (Test-Path $indexPath) {
        Write-Host "Opening $indexPath in your browser..." -ForegroundColor Cyan
        try {
            Start-Process -FilePath $indexPath -ErrorAction Stop
        }
        catch {
            Write-Host "Warning: Could not open browser automatically. Please open the file manually." -ForegroundColor Yellow
            Write-Host "HTML files are available at: $indexPath" -ForegroundColor White
        }
    } else {
        Write-Host "Warning: No index.html or README.html found to open automatically." -ForegroundColor Yellow
    }
} else {
    Write-Host "Skipping automatic browser opening. You can manually open index.html in your browser from $OutputDir" -ForegroundColor Yellow
}