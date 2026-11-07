# Копирует модели и библиотеку sherpa-onnx в проект Android Studio.
# Запуск (PowerShell):  .\setup_models.ps1
param(
    [string]$Project   = "C:\hackathon\NeuroSteno",
    [string]$Downloads = "C:\hackathon\downloads"
)
$ErrorActionPreference = "Stop"

function Pick($dir, $names) {
    foreach ($n in $names) {
        $p = Join-Path $dir $n
        if (Test-Path $p) { return $p }
    }
    throw "В папке $dir не найден ни один из файлов: $($names -join ', ')"
}

$asr = Join-Path $Downloads "sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16"
$seg = Join-Path $Downloads "sherpa-onnx-pyannote-segmentation-3-0"
$emb = Join-Path $Downloads "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
$aar = Join-Path $Downloads "sherpa-onnx-2.0.1.aar"

$models = Join-Path $Project "app\src\main\assets\models"
$libs   = Join-Path $Project "app\libs"
New-Item -ItemType Directory -Force -Path "$models\asr", "$models\seg", "$models\emb", $libs | Out-Null

Copy-Item (Pick $asr @("model.int8.onnx", "model.onnx")) "$models\asr\model.onnx" -Force
Copy-Item (Join-Path $asr "tokens.txt")                  "$models\asr\tokens.txt" -Force
Copy-Item (Pick $seg @("model.int8.onnx", "model.onnx")) "$models\seg\model.onnx" -Force
Copy-Item $emb                                           "$models\emb\model.onnx" -Force
Copy-Item $aar (Join-Path $libs "sherpa-onnx-2.0.1.aar") -Force

Write-Host "Готово. Размеры файлов:"
Get-ChildItem -Recurse $models, $libs -File | ForEach-Object {
    "{0,10:N1} МБ  {1}" -f ($_.Length / 1MB), $_.FullName.Replace($Project, "")
}
