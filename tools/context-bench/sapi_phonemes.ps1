# Writes "word<TAB>SAPI phoneme ids" for every line of <inp> (Windows System.Speech, voice Zira).
# usage: powershell -File sapi_phonemes.ps1 <words.txt> <out.tsv>   (Windows paths)
param([string]$inp, [string]$out)
Add-Type -AssemblyName System.Speech
$code = @"
using System; using System.Text; using System.Collections.Generic; using System.Speech.Synthesis;
public static class Phon {
  public static void Run(string inp, string outp) {
    var s = new SpeechSynthesizer();
    s.SelectVoice("Microsoft Zira Desktop");
    s.SetOutputToNull();
    var cur = new List<string>();
    s.PhonemeReached += (o, e) => { lock (cur) { cur.Add(((int)e.Phoneme[0]).ToString()); } };
    using (var w = new System.IO.StreamWriter(outp, false, new UTF8Encoding(false))) {
      foreach (var word in System.IO.File.ReadAllLines(inp, Encoding.UTF8)) {
        lock (cur) cur.Clear();
        s.Speak(word);
        System.Threading.Thread.Sleep(1);
        string p; lock (cur) p = String.Join(" ", cur);
        w.WriteLine(word + "\t" + p);
      }
    }
  }
}
"@
Add-Type -TypeDefinition $code -ReferencedAssemblies System.Speech
[Phon]::Run($inp, $out)
