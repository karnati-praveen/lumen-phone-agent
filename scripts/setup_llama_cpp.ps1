$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$LlamaDir = Join-Path $RootDir "android-app\app\src\main\cpp\third_party\llama.cpp"
$PatchFile = Join-Path $RootDir "android-app\app\src\main\cpp\patches\llama-cpp-mtmd-fd-stream.patch"
$LlamaCommit = "244640b7f26e2a7301b27a24edc4f7feaf97d52f"

if (-not (Test-Path (Join-Path $LlamaDir ".git"))) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LlamaDir) | Out-Null
    git clone https://github.com/ggml-org/llama.cpp.git $LlamaDir
}

git -C $LlamaDir fetch origin
git -C $LlamaDir checkout $LlamaCommit

git -C $LlamaDir apply --reverse --check $PatchFile *> $null
if ($LASTEXITCODE -ne 0) {
    git -C $LlamaDir apply $PatchFile
    Write-Output "Applied mtmd fd:// patch cleanly."
} else {
    Write-Output "mtmd fd:// patch already applied."
}
