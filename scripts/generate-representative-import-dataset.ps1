param(
    [string]$OutputPath = $(if ($env:NOVEL_AGENT_IMPORT_DATASET_PATH) {
            $env:NOVEL_AGENT_IMPORT_DATASET_PATH
        } else {
            "artifacts/import-benchmark-representative-20260811.jsonl"
        }),
    [ValidateRange(5, 100000)]
    [int]$RecordCount = 1000
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Decode-Utf8 {
    param([Parameter(Mandatory = $true)][string]$Base64Value)

    return [System.Text.Encoding]::UTF8.GetString(
        [System.Convert]::FromBase64String($Base64Value)
    )
}

if ($RecordCount % 5 -ne 0) {
    throw "RecordCount must be divisible by 5 so the five writing scenarios remain balanced."
}

$resolvedOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$parentPath = Split-Path -Parent $resolvedOutputPath
if ($parentPath -and -not (Test-Path -LiteralPath $parentPath)) {
    New-Item -ItemType Directory -Path $parentPath -Force | Out-Null
}

$characters = @(
    (Decode-Utf8 "5rKI56Ca"), (Decode-Utf8 "6aG+5riF5ryq"),
    (Decode-Utf8 "6ZmG5rKJ6Iif"), (Decode-Utf8 "6IuP5pma"),
    (Decode-Utf8 "6LCi5Li05bed"), (Decode-Utf8 "5a6B5pit")
)
$locations = @(
    (Decode-Utf8 "6buR55qH5Z+O"), (Decode-Utf8 "5peg56m65bGx"),
    (Decode-Utf8 "5qW85YWw5Y+k5Z+O"), (Decode-Utf8 "54Gr56We5rSe5aSp"),
    (Decode-Utf8 "5aSp5p6B6Zeo"), (Decode-Utf8 "5pyI5b2x5a6X")
)
$artifacts = @(
    (Decode-Utf8 "6b6Z57q55Y+k5Y2w"), (Decode-Utf8 "5YKo54mp5oiS"),
    (Decode-Utf8 "56ym5ZKS5Y236L20"), (Decode-Utf8 "5LiD5pif5YmR"),
    (Decode-Utf8 "546E5Yal54Gv"), (Decode-Utf8 "5b2S5aKf5Luk")
)
$events = @(
    (Decode-Utf8 "5aSp5p6B6Zeo6KaG54Gt"), (Decode-Utf8 "5pyI5b2x5a6X5YaF5Lmx"),
    (Decode-Utf8 "54Gr56We5rSe5aSp5byA5ZCv"), (Decode-Utf8 "6buR55qH5Z+O5bCB6ZSB"),
    (Decode-Utf8 "5qW85YWw5Y+k5Z+O5o2i6Ziy"), (Decode-Utf8 "5peg56m65bGx5aSx6Liq5qGI")
)
$skills = @(
    (Decode-Utf8 "54G15oGv5oSf55+l"), (Decode-Utf8 "5LiD5pif5YmR6K+A"),
    (Decode-Utf8 "546E5Yal54K85L2T"), (Decode-Utf8 "5b2x6YGB5pyv"),
    (Decode-Utf8 "56ym6Zi15o6o5ryU"), (Decode-Utf8 "5b2S5aKf5byV")
)
$instructions = @(
    (Decode-Utf8 "5pW055CG5Lq654mp5YWz57O744CB5Yqo5py65LiO5b2T5YmN54q25oCB77yM5b2i5oiQ5Y+v5L6b57ut5YaZ5qOA57Si55qE5YaZ5L2c6K6w5b+G44CC"),
    (Decode-Utf8 "5o+Q5Y+W5pyq6Zet5ZCI5LqL5Lu244CB5LyP56yU5ZKM5ZCO57ut5b2x5ZON77yM5b2i5oiQ5Y+v6L+96Liq55qE5Ymn5oOF6K6w5b+G44CC"),
    (Decode-Utf8 "5pW055CG5LiW55WM6KeC5Zyw54K544CB6KeE5YiZ5LiO6ZmQ5Yi277yM5b2i5oiQ56ug6IqC5oSf55+l55qE6K6+5a6a6K6w5b+G44CC"),
    (Decode-Utf8 "5pW055CG6YGT5YW344CB5oqA6IO955qE5p2l5rqQ44CB6IO95Yqb6L6555WM5LiO5Luj5Lu377yM5b2i5oiQ5Y+v5o6n55Sf5oiQ6K6w44CC"),
    (Decode-Utf8 "57uT5ZCI5b2T5YmN56ug6IqC5LiK5LiL5paH77yM5pW055CG5LiL5LiA5q6157ut5YaZ6ZyA6KaB6YG15a6I55qE57qm5p2f44CC")
)
$inputTemplates = @(
    (Decode-Utf8 "5Zy65pmv57G75Z6L77ya5Lq654mp5YWz57O777yb56ug6IqC77yaezB977yb5Lq654mp77yaezF9IOS4jiB7Mn3vvJvlnLDngrnvvJp7M33jgIJ7MX0g5Zyo5pys56ug6KGo6Z2i5LiK5LiOIHsyfSDnu5Pnm5/vvIzlrp7pmYXku43lm6DkuIrkuIDnq6DnmoTor6/kvJrkv53mjIHmiJLlpIfjgII="),
    (Decode-Utf8 "5Zy65pmv57G75Z6L77ya5pyq6Zet5ZCI5LqL5Lu277yb56ug6IqC77yaezB977yb5LqL5Lu277yaezF977yb5YWz6IGU5Zyw54K577yaezJ977yb5YWz6IGU6YGT5YW377yaezN944CC5LqL5Lu25Zyo5pys56ug5Y+q5o+t56S65LqG6KGo6LGh77yM5YWz6ZSu6K+B5o2u5bCa5pyq5Ye6546w44CC"),
    (Decode-Utf8 "5Zy65pmv57G75Z6L77ya5LiW55WM6K6+5a6a77yb56ug6IqC77yaezB977yb5Zyw54K577yaezF977yb5pys56ug5raJ5Y+K54G15Yqb6YCa6KGM44CB5Yy65Z+f6L6555WM5ZKM5pmu6YCa5Lq66L+b5YWl6ZmQ5Yi244CC"),
    (Decode-Utf8 "5Zy65pmv57G75Z6L77ya6YGT5YW35oqA6IO977yb56ug6IqC77yaezB977yb6YGT5YW377yaezF977yb5oqA6IO977yaezJ977yb5L2/55So6ICF77yaezN944CC6K+l6IO95Yqb5pu+5ZyoIHs0fSDov5vooYzov4fkuIDmrKHkuI3lrozmlbTmtYvor5XjgII="),
    (Decode-Utf8 "5Zy65pmv57G75Z6L77ya57ut5YaZ5LiK5LiL5paH77yb56ug6IqC77yaezB977yb5b2T5YmN5Zyw54K577yaezF977yb5b2T5YmN5Lq654mp77yaezJ977yb5YmN572u5LqL5Lu277yaezN944CC5Zy65pmv5aSE5LqO6KGM5Yqo5YmN55qE55+t5pqC5Yaz562W56qX5Y+j44CC")
)
$outputTemplates = @(
    (Decode-Utf8 "5YaZ5L2c6K6w5b+G77yaezB9IOeahOaguOW/g+ebruagh+aYr+afpeaYjiB7MX0g55qE5bmV5ZCO5Y6f5Zug77ybezJ9IOaOjOaPoeS4gOadoeS4jeWujOaVtOe6v+e0ouOAguS4pOS6uuWPquiDvemAmui/h+S6pOaNouaciemZkOS/oeaBr+aOqOi/m+WJp+aDhe+8jOS4jeiDveebtOaOpea2iOmZpOefm+ebvu+8m+WQjue7reWGsueqgeW6lOS/neeVmeS/oeS7u+WPmOWMluWSjOWKqOacuuWPjei9rOepuumXtOOAgg=="),
    (Decode-Utf8 "5Ymn5oOF6K6w5b+G77yaezB9IOaYr+acqumXreWQiOS8j+eslO+8jHsxfSDlnKjnjrDlnLrnlZnkuIvkuobkuI4gezJ9IOacieWFs+eahOW8guW4uOiusOW9leOAguWQjue7reeroOiKgumcgOimgeWFiOihpem9kOivgeaNru+8jOWGjeWGs+WumuS6i+S7tuaYr+WQpuWNh+e6p+S4uuWFrOW8gOWGsueqge+8m+WcqOS8j+eslOmXreWQiOWJje+8jOS4jeW6lOeUn+aIkOehruWumuaAp+e7k+iuuuOAgg=="),
    (Decode-Utf8 "6K6+5a6a6K6w5b+G77yaezB9IOeahOaguOW/g+inhOWImeaYr+i/m+WFpeiAheW/hemhu+mBteWuiOWknOmXtOemgeihjOS4jueBteWKm+WIhue6p+WItuW6pu+8jOWkluadpeiAheaXoOazleebtOaOpeiwg+eUqOW9k+WcsOmYteazleOAgnsxfSDlj6/ku6Xop4Llr5/op4TliJnmvI/mtJ7vvIzkvYbkuI3og73ml6Dku6Pku7fnu5Xov4fovrnnlYzvvJvnu63lhpnlupTkv53mjIHorr7lrprkuIDoh7TlubbkvZPnjrDop4TliJnluKbmnaXnmoTliafmg4XmiJDmnKzjgII="),
    (Decode-Utf8 "6IO95Yqb6K6w5b+G77yaezB9IOWPr+S7peaUvuWkpyB7MX0g55qE5oSf55+l6IyD5Zu077yM5L2G5q+P5qyh5L2/55So6YO95Lya5raI6ICX55+t5pyf6K6w5b+G5bm255WZ5LiL5Y+v6L+96Liq55qE54G15Yqb5rOi5Yqo44CCezJ9IOebruWJjeWPquiDvei/m+ihjOS9juW8uuW6puS9v+eUqO+8jOS4jeiDveWHreepuuiOt+W+l+i2hei2iuW9k+WJjeWig+eVjOeahOiDveWKm++8m+eUn+aIkOaImOaWl+aIluino+iwnOe7k+aenOaXtuW/hemhu+S9k+eOsOS7o+S7t+OAgg=="),
    (Decode-Utf8 "57ut5YaZ57qm5p2f77ya5LiL5LiA5q615bqU5LuOezB955qE6KeC5a+f5oiW6YCJ5oup5byA5aeL77yM5YWI5aSE55CGezF955WZ5LiL55qE55u05o6l5ZCO5p6c77yM5YaN5o6o6L+bezJ95Lit55qE6KGM5Yqo44CC5LiN5b6X5Yet56m65byV5YWl5paw55qE5qC45b+D5Yq/5Yqb77yM5LiN5b6X5o+Q5YmN6Zet5ZCI5pyq6Kej6YeK57q/57Si77yb5bqU5L+d55WZ6Iez5bCR5LiA5Liq5Y+v5Zue5pS255qE5oKs5b+144CC")
)
$records = [System.Collections.Generic.List[string]]::new()

for ($index = 1; $index -le $RecordCount; $index++) {
    $scenarioIndex = ($index - 1) % 5
    $chapter = 1 + (($index - 1) % 200)
    $character = $characters[($index - 1) % $characters.Count]
    $secondCharacter = $characters[$index % $characters.Count]
    $location = $locations[($index - 1) % $locations.Count]
    $artifact = $artifacts[($index - 1) % $artifacts.Count]
    $event = $events[($index - 1) % $events.Count]
    $skill = $skills[($index - 1) % $skills.Count]
    $instruction = $instructions[$scenarioIndex]

    switch ($scenarioIndex) {
        0 {
            $input = $inputTemplates[0] -f $chapter, $character, $secondCharacter, $location
            $output = $outputTemplates[0] -f $character, $event, $secondCharacter
        }
        1 {
            $input = $inputTemplates[1] -f $chapter, $event, $location, $artifact
            $output = $outputTemplates[1] -f $event, $character, $artifact
        }
        2 {
            $input = $inputTemplates[2] -f $chapter, $location
            $output = $outputTemplates[2] -f $location, $character
        }
        3 {
            $input = $inputTemplates[3] -f $chapter, $artifact, $skill, $character, $location
            $output = $outputTemplates[3] -f $artifact, $skill, $character
        }
        4 {
            $input = $inputTemplates[4] -f $chapter, $location, $character, $event
            $output = $outputTemplates[4] -f $character, $event, $location
        }
    }

    $record = [ordered]@{
        instruction = $instruction
        input = $input
        output = $output
    }
    $records.Add(($record | ConvertTo-Json -Compress))
}

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllLines($resolvedOutputPath, $records.ToArray(), $utf8NoBom)

[ordered]@{
    outputPath = $resolvedOutputPath
    sourceRecordCount = $RecordCount
    expectedSegmentCount = $RecordCount * 2
    scenarioCounts = [ordered]@{
        character_profile = $RecordCount / 5
        unresolved_event = $RecordCount / 5
        world_setting = $RecordCount / 5
        item_skill = $RecordCount / 5
        prewriting_context = $RecordCount / 5
    }
    deterministic = $true
    corpusScope = "schema-aligned synthetic Chinese writing-memory corpus; not production source text"
} | ConvertTo-Json -Depth 5
