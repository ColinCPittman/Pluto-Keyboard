# Speaks every line of items.json (from bench.py) with the Windows voices into 16 kHz mono WAVs.
# "|" in a line is a pause of about a second, and an item's optional "rate" ("115%") sets the
# speaking rate. Usage: tts.ps1 <items.json> <out dir>
# WAVs that already exist are kept, so delete the folder after changing sentences.py.
param([Parameter(Mandatory)][string]$Items, [Parameter(Mandatory)][string]$Out)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
New-Item -ItemType Directory -Force $Out | Out-Null
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$all = Get-Content -Raw -Encoding UTF8 -LiteralPath $Items | ConvertFrom-Json
# SSML falls back to the default voice when a name is missing, which would quietly change the bench
$installed = $s.GetInstalledVoices() | ForEach-Object { $_.VoiceInfo.Name }
$missing = $all | ForEach-Object { $_.voice } | Sort-Object -Unique | Where-Object { $_ -notin $installed }
if ($missing) { throw "Voices not installed: $($missing -join ', '). Installed: $($installed -join ', ')" }
try {
    foreach ($it in $all) {
        $wav = Join-Path $Out ($it.id + ".wav")
        if (Test-Path -LiteralPath $wav) { continue }
        $body = ($it.say -split '\|' | ForEach-Object { [System.Security.SecurityElement]::Escape($_.Trim()) }) -join ' <break time="900ms"/> '
        # an item may carry a speaking rate ("115%"); without one the voice speaks at its own rate
        if ($it.PSObject.Properties['rate'] -and $it.rate) { $body = '<prosody rate="' + $it.rate + '">' + $body + '</prosody>' }
        $ssml = '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US"><voice name="' + $it.voice + '">' + $body + '</voice></speak>'
        try {
            $s.SetOutputToWaveFile($wav, $fmt)
            $s.SpeakSsml($ssml)
            $s.SetOutputToNull()
        } catch {
            $s.SetOutputToNull()
            Remove-Item -LiteralPath $wav -ErrorAction SilentlyContinue  # no half-written WAV to skip next time
            throw
        }
    }
} finally {
    $s.Dispose()
}
