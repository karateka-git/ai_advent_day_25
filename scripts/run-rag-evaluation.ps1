param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$DatasetPath = "",
    [string]$InputDir = "./docs/articles/doroshevich",
    [string]$Strategy = "structured",
    [int]$TopK = 3,
    [string]$OutputPath = "",
    [string]$RawOutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $DatasetPath) {
    $DatasetPath = Join-Path $ProjectRoot "docs\control-questions.json"
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $ProjectRoot "data\rag-evaluation-summary.md"
}
if (-not $RawOutputPath) {
    $RawOutputPath = Join-Path $ProjectRoot "data\rag-evaluation-raw.md"
}

$traceLogPaths = @(
    (Join-Path $ProjectRoot "data\logs\rag-trace.jsonl"),
    (Join-Path $ProjectRoot "data\logs\rag-trace-readable.md")
)

$ragModes = @(
    @{ Id = "model-rerank"; Label = "RAG Model Rerank" }
)

$stopWords = @(
    "и","в","во","на","с","со","к","ко","по","из","за","от","до","не","но","а","или",
    "что","это","как","кто","для","под","при","над","об","уже","его","ее","её","они","она","он",
    "там","тут","этот","эта","эти","так","ли","же","мы","вы","я","ты","мне","их"
)

function Get-CliBatPath {
    param([string]$WorkingDirectory)
    Join-Path $WorkingDirectory "build\install\ai_advent_day_24\bin\local-document-indexer.bat"
}

function Invoke-CliCommand {
    param(
        [string]$WorkingDirectory,
        [string]$BatPath,
        [string[]]$Arguments
    )

    $escapedArgs = $Arguments | ForEach-Object {
        if ($_ -match '\s|"') {
            '"' + ($_ -replace '"', '\"') + '"'
        } else {
            $_
        }
    }
    $joinedArgs = [string]::Join(" ", $escapedArgs)

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "cmd.exe"
    $psi.Arguments = "/c chcp 65001>nul && `"$BatPath`" $joinedArgs"
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    try {
        $process.Start() | Out-Null
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        [PSCustomObject]@{
            ExitCode = $process.ExitCode
            Stdout = $stdout
            Stderr = $stderr
        }
    } finally {
        $process.Dispose()
    }
}

function Throw-EvaluationFailure {
    param(
        [string]$Stage,
        [string]$CommandDescription,
        [string]$Stdout,
        [string]$Stderr
    )

    throw "$Stage failed for command: $CommandDescription`n$Stdout`n$Stderr"
}

function Normalize-CommandOutput {
    param([string]$Text)

    $normalized = $Text -replace "`r", ""
    $normalized = [regex]::Replace($normalized, "(?m)^Ответ модели.*$", "")
    return $normalized.Trim()
}

function Extract-AnswerBody {
    param([string]$Output)

    $normalized = Normalize-CommandOutput -Text $Output
    $match = [regex]::Match($normalized, '(?s)Ответ:\s*(.*?)(?:\s*Источники:|\s*Цитаты:|\s*Retrieval-сводка:|\s*Retrieval:|$)')
    return $match.Groups[1].Value.Trim()
}

function Extract-SourcesBlock {
    param([string]$Output)

    $normalized = Normalize-CommandOutput -Text $Output
    $match = [regex]::Match($normalized, '(?s)Источники:\s*(.*?)(?:\s*Цитаты:|\s*Retrieval-сводка:|\s*Retrieval:|$)')
    return $match.Groups[1].Value.Trim()
}

function Extract-QuotesBlock {
    param([string]$Output)

    $normalized = Normalize-CommandOutput -Text $Output
    $match = [regex]::Match($normalized, '(?s)Цитаты:\s*(.*?)(?:\s*Retrieval-сводка:|\s*Retrieval:|$)')
    return $match.Groups[1].Value.Trim()
}

function Extract-RetrievalBody {
    param([string]$Output)

    $normalized = Normalize-CommandOutput -Text $Output
    $match = [regex]::Match($normalized, '(?s)Retrieval-сводка:\s*(.*)$')
    if (-not [string]::IsNullOrWhiteSpace($match.Groups[1].Value)) {
        return $match.Groups[1].Value.Trim()
    }
    $match = [regex]::Match($normalized, '(?s)Retrieval:\s*(.*)$')
    return $match.Groups[1].Value.Trim()
}

function Get-StructuredEntryCount {
    param(
        [string]$Block,
        [string]$Pattern
    )

    if ([string]::IsNullOrWhiteSpace($Block)) { return 0 }
    return [regex]::Matches($Block, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline).Count
}

function Get-QuoteTextsFromBlock {
    param([string]$QuotesBlock)

    if ([string]::IsNullOrWhiteSpace($QuotesBlock)) { return "" }
    $texts = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($QuotesBlock -split "\n")) {
        if ($line -match '^\s*quote\s*=\s*(.+)$') {
            $texts.Add($matches[1].Trim())
        }
    }
    return [string]::Join(' ', $texts).Trim()
}

function Get-NormalizedTerms {
    param([string]$Text)

    $terms = New-Object System.Collections.Generic.HashSet[string]
    if ([string]::IsNullOrWhiteSpace($Text)) { return $terms }
    foreach ($match in [regex]::Matches($Text.ToLowerInvariant(), '[\p{L}\p{Nd}]+')) {
        $token = $match.Value
        if ($token.Length -lt 3) { continue }
        if ($stopWords -contains $token) { continue }
        [void]$terms.Add($token)
    }
    return $terms
}

function Get-ExpectationTerms {
    param([string[]]$Expectation)

    $terms = New-Object System.Collections.Generic.HashSet[string]
    foreach ($line in $Expectation) {
        foreach ($term in (Get-NormalizedTerms -Text $line)) {
            [void]$terms.Add($term)
        }
    }
    return $terms
}

function Get-FilePathsFromRetrieval {
    param([string]$RetrievalBody)

    $paths = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($RetrievalBody -split "\n")) {
        if ($line -match 'filePath = (.+)$') {
            $paths.Add($matches[1].Trim())
        }
    }
    return $paths
}

function Test-AnswerLooksLikeRefusal {
    param([string]$Answer)

    return [regex]::IsMatch($Answer, '(?i)(\bне знаю\b)|(\bне могу ответить\b)|(контекст не найден)|(данных недостаточно)|(недостаточно данных)|(не удалось найти)|(уточните вопрос)')
}

function Get-AnswerCoverageRatio {
    param(
        [System.Collections.Generic.HashSet[string]]$ExpectationTerms,
        [string]$Answer
    )

    if ($ExpectationTerms.Count -eq 0) { return 0.0 }
    $answerTerms = Get-NormalizedTerms -Text $Answer
    $intersectionCount = 0
    foreach ($term in $ExpectationTerms) {
        if ($answerTerms.Contains($term)) { $intersectionCount++ }
    }
    return ($intersectionCount / [double]$ExpectationTerms.Count)
}

function Get-QuoteAlignmentScore {
    param(
        [string]$Answer,
        [string]$QuotesBlock
    )

    $quoteTexts = Get-QuoteTextsFromBlock -QuotesBlock $QuotesBlock
    if ([string]::IsNullOrWhiteSpace($quoteTexts)) { return 0.0 }
    $answerTerms = Get-NormalizedTerms -Text $Answer
    $quoteTerms = Get-NormalizedTerms -Text $quoteTexts
    if ($answerTerms.Count -eq 0 -or $quoteTerms.Count -eq 0) { return 0.0 }
    $intersectionCount = 0
    foreach ($term in $quoteTerms) {
        if ($answerTerms.Contains($term)) { $intersectionCount++ }
    }
    return ($intersectionCount / [double]$quoteTerms.Count)
}

function Get-SourceEntryCount {
    param([string]$SourcesBlock)
    return Get-StructuredEntryCount -Block $SourcesBlock -Pattern '^\s*\d+\.\s+source\s*='
}

function Get-QuoteEntryCount {
    param([string]$QuotesBlock)
    return Get-StructuredEntryCount -Block $QuotesBlock -Pattern '^\s*\d+\.\s+chunkId\s*='
}

function Test-HasExpectedSource {
    param(
        [System.Collections.Generic.List[string]]$RetrievalFilePaths,
        [string[]]$ExpectedSources
    )

    foreach ($expectedSource in $ExpectedSources) {
        foreach ($path in $RetrievalFilePaths) {
            if ($path -eq $expectedSource -or $path.EndsWith($expectedSource)) { return $true }
        }
    }
    return $false
}

function Evaluate-RunResult {
    param(
        [string]$ModeId,
        [string]$Answer,
        [string]$SourcesBlock,
        [string]$QuotesBlock,
        [string]$RetrievalBody,
        [string[]]$ExpectedSources,
        [System.Collections.Generic.HashSet[string]]$ExpectationTerms
    )

    $retrievalFilePaths = Get-FilePathsFromRetrieval -RetrievalBody $RetrievalBody
    $hasExpectedSource = Test-HasExpectedSource -RetrievalFilePaths $retrievalFilePaths -ExpectedSources $ExpectedSources
    $coverageRatio = Get-AnswerCoverageRatio -ExpectationTerms $ExpectationTerms -Answer $Answer
    $sourceCount = Get-SourceEntryCount -SourcesBlock $SourcesBlock
    $quoteCount = Get-QuoteEntryCount -QuotesBlock $QuotesBlock
    $hasSourcesBlock = $sourceCount -gt 0
    $hasQuotesBlock = $quoteCount -gt 0
    $quoteAlignmentScore = Get-QuoteAlignmentScore -Answer $Answer -QuotesBlock $QuotesBlock
    $looksLikeRefusal = Test-AnswerLooksLikeRefusal -Answer $Answer
    $refusalIsReasonable = $looksLikeRefusal -and -not $hasExpectedSource

    $score = 0
    if ($hasExpectedSource) { $score += 2 } else { $score -= 1 }
    if ($hasSourcesBlock) { $score += 2 } else { $score -= 1 }
    if ($hasQuotesBlock) { $score += 2 } else { $score -= 1 }
    if ($quoteAlignmentScore -ge 0.45) { $score += 2 } elseif ($quoteAlignmentScore -ge 0.20) { $score += 1 } else { $score -= 1 }
    if ($looksLikeRefusal) {
        if ($refusalIsReasonable) { $score += 1 } else { $score -= 1 }
    }

    $verdict = if ($hasExpectedSource -and $hasSourcesBlock -and $hasQuotesBlock -and $quoteAlignmentScore -ge 0.25 -and -not $looksLikeRefusal) {
        'success'
    } elseif ($refusalIsReasonable -and -not $hasSourcesBlock -and -not $hasQuotesBlock) {
        'partial'
    } elseif ($hasExpectedSource -or $hasSourcesBlock -or $hasQuotesBlock -or $quoteAlignmentScore -ge 0.15) {
        'partial'
    } else {
        'fail'
    }

    $noteParts = New-Object System.Collections.Generic.List[string]
    if ($hasExpectedSource) { $noteParts.Add('retrieval contains the expected source') } else { $noteParts.Add('expected source missing in retrieval') }
    $noteParts.Add("sources block count $sourceCount")
    $noteParts.Add("quotes block count $quoteCount")
    $noteParts.Add(("quote alignment {0:P0}" -f $quoteAlignmentScore))
    $noteParts.Add(("answer coverage {0:P0}" -f $coverageRatio))
    if ($looksLikeRefusal) {
        if ($refusalIsReasonable) { $noteParts.Add('answer looks like a grounded refusal') } else { $noteParts.Add('answer looks like an avoidable refusal') }
    } else {
        $noteParts.Add('answer looks like a regular grounded response')
    }

    return [PSCustomObject]@{
        ModeId = $ModeId
        Verdict = $verdict
        Score = $score
        CoverageRatio = $coverageRatio
        HasExpectedSource = $hasExpectedSource
        SourceCount = $sourceCount
        QuoteCount = $quoteCount
        HasSourcesBlock = $hasSourcesBlock
        HasQuotesBlock = $hasQuotesBlock
        QuoteAlignmentScore = $quoteAlignmentScore
        LooksLikeRefusal = $looksLikeRefusal
        RefusalIsReasonable = $refusalIsReasonable
        Note = [string]::Join('; ', $noteParts)
        RetrievalFilePaths = $retrievalFilePaths
    }
}

function New-RawSection {
    param(
        [int]$Id,
        [string]$Question,
        $Expectation,
        $ExpectedSources,
        $RunResults
    )

    $lines = @()
    $lines += "## $Id. $Question"
    $lines += ''
    $lines += 'Expectation:'
    foreach ($item in $Expectation) { $lines += "- $item" }
    $lines += 'Expected sources:'
    foreach ($item in $ExpectedSources) { $lines += "- $item" }
    $lines += ''

    foreach ($run in $RunResults) {
        $lines += "### $($run.Label)"
        $lines += "Verdict: $($run.Evaluation.Verdict) | Score: $($run.Evaluation.Score)"
        $lines += "Note: $($run.Evaluation.Note)"
        $lines += ("Signals: expected source={0}; sources block={1} ({2}); quotes block={3} ({4}); quote alignment={5:P0}; refusal={6}" -f
            $run.Evaluation.HasExpectedSource,
            $run.Evaluation.HasSourcesBlock,
            $run.Evaluation.SourceCount,
            $run.Evaluation.HasQuotesBlock,
            $run.Evaluation.QuoteCount,
            $run.Evaluation.QuoteAlignmentScore,
            $run.Evaluation.LooksLikeRefusal)
        $lines += ''
        $lines += 'Answer:'
        $lines += '```text'
        $lines += (($run.AnswerBody | Out-String).Trim())
        $lines += '```'
        $lines += ''
        $lines += 'Sources:'
        $lines += '```text'
        $lines += (($run.SourcesBlock | Out-String).Trim())
        $lines += '```'
        $lines += ''
        $lines += 'Quotes:'
        $lines += '```text'
        $lines += (($run.QuotesBlock | Out-String).Trim())
        $lines += '```'
        $lines += ''
        $lines += 'Retrieval:'
        $lines += '```text'
        $lines += (($run.RetrievalBody | Out-String).Trim())
        $lines += '```'
        $lines += ''
    }

    return [string]::Join("`n", $lines)
}

function New-SummarySection {
    param(
        $QuestionRecord,
        $ModeResults,
        $PlainResult
    )

    $modeCount = $ModeResults.Count
    $successCount = @($ModeResults | Where-Object { $_.Evaluation.Verdict -eq 'success' }).Count
    $partialCount = @($ModeResults | Where-Object { $_.Evaluation.Verdict -eq 'partial' }).Count
    $bestScore = (($ModeResults | ForEach-Object { $_.Evaluation.Score }) | Measure-Object -Maximum).Maximum
    $bestModes = @($ModeResults | Where-Object { $_.Evaluation.Score -eq $bestScore } | ForEach-Object { $_.Label })
    $sourcesCount = @($ModeResults | Where-Object { $_.Evaluation.HasSourcesBlock }).Count
    $quotesCount = @($ModeResults | Where-Object { $_.Evaluation.HasQuotesBlock }).Count
    $alignmentCount = @($ModeResults | Where-Object { $_.Evaluation.QuoteAlignmentScore -ge 0.25 }).Count
    $refusalCount = @($ModeResults | Where-Object { $_.Evaluation.LooksLikeRefusal }).Count

    $questionConclusion = if ($successCount -gt 0) {
        "There are working RAG modes; the strongest result came from: $([string]::Join(', ', $bestModes))."
    } elseif ($partialCount -gt 0) {
        "There is no full success yet, but these modes achieved partial matches: $([string]::Join(', ', $bestModes))."
    } else {
        'No RAG mode produced a confident result; retrieval still looks like the main weak point.'
    }

    $lines = @()
    $lines += "## $($QuestionRecord.id). $($QuestionRecord.question)"
    $lines += ''
    $lines += 'Expectation:'
    foreach ($item in $QuestionRecord.expectation) { $lines += "- $item" }
    $lines += 'Expected sources:'
    foreach ($item in $QuestionRecord.expectedSources) { $lines += "- $item" }
    $lines += ''
    $lines += '| Mode | Verdict | Score | Sources | Quotes | Align | Refusal | Note |'
    $lines += '|---|---|---:|---|---|---:|---|---|'
    $lines += "| Plain | $($PlainResult.Evaluation.Verdict) | $($PlainResult.Evaluation.Score) | - | - | - | $($PlainResult.Evaluation.LooksLikeRefusal) | $($PlainResult.Evaluation.Note) |"
    foreach ($modeResult in $ModeResults) {
        $lines += ("| {0} | {1} | {2} | {3} | {4} | {5:P0} | {6} | {7} |" -f
            $modeResult.Label,
            $modeResult.Evaluation.Verdict,
            $modeResult.Evaluation.Score,
            $modeResult.Evaluation.HasSourcesBlock,
            $modeResult.Evaluation.HasQuotesBlock,
            $modeResult.Evaluation.QuoteAlignmentScore,
            $modeResult.Evaluation.LooksLikeRefusal,
            $modeResult.Evaluation.Note)
    }
    $lines += ''
    $lines += "- RAG successes: $successCount/$modeCount"
    $lines += "- RAG partials: $partialCount/$modeCount"
    $lines += "- RAG sources present: $sourcesCount/$modeCount"
    $lines += "- RAG quotes present: $quotesCount/$modeCount"
    $lines += "- Quote alignment passed: $alignmentCount/$modeCount"
    $lines += "- Refusals: $refusalCount/$modeCount"
    $lines += "- Best modes: $([string]::Join(', ', $bestModes))"
    $lines += "- Conclusion: $questionConclusion"
    $lines += ''

    return [string]::Join("`n", $lines)
}

Set-Location $ProjectRoot

foreach ($traceLogPath in $traceLogPaths) {
    if (Test-Path -LiteralPath $traceLogPath) {
        Remove-Item -LiteralPath $traceLogPath -Force
    }
}

$cliBatPath = Get-CliBatPath -WorkingDirectory $ProjectRoot
if (-not (Test-Path -LiteralPath $cliBatPath)) {
    throw "Built CLI launcher not found: $cliBatPath. Run start-manual-check.ps1 first."
}

$questions = Get-Content -LiteralPath $DatasetPath -Encoding UTF8 | ConvertFrom-Json

$indexCommand = @('index', '--input', $InputDir, '--strategy', $Strategy)
$indexResult = Invoke-CliCommand -WorkingDirectory $ProjectRoot -BatPath $cliBatPath -Arguments $indexCommand
if ($indexResult.ExitCode -ne 0) {
    Throw-EvaluationFailure -Stage 'Indexing' -CommandDescription ($indexCommand -join ' ') -Stdout $indexResult.Stdout -Stderr $indexResult.Stderr
}

$summarySections = New-Object System.Collections.ArrayList
$rawSections = New-Object System.Collections.ArrayList
$modeWinCounters = @{}
foreach ($mode in $ragModes) { $modeWinCounters[$mode.Id] = 0 }

foreach ($question in $questions) {
    $expectationTerms = Get-ExpectationTerms -Expectation ([string[]]$question.expectation)

    $plainRun = Invoke-CliCommand -WorkingDirectory $ProjectRoot -BatPath $cliBatPath -Arguments @('ask', '--query', [string]$question.question, '--mode', 'plain')
    if ($plainRun.ExitCode -ne 0) {
        Throw-EvaluationFailure -Stage 'Plain evaluation' -CommandDescription "ask --query $($question.question) --mode plain" -Stdout $plainRun.Stdout -Stderr $plainRun.Stderr
    }

    $plainAnswer = Extract-AnswerBody -Output $plainRun.Stdout
    $plainSources = Extract-SourcesBlock -Output $plainRun.Stdout
    $plainQuotes = Extract-QuotesBlock -Output $plainRun.Stdout
    $plainRetrieval = Extract-RetrievalBody -Output $plainRun.Stdout
    $plainEvaluation = Evaluate-RunResult -ModeId 'plain' -Answer $plainAnswer -SourcesBlock $plainSources -QuotesBlock $plainQuotes -RetrievalBody $plainRetrieval -ExpectedSources ([string[]]$question.expectedSources) -ExpectationTerms $expectationTerms
    $plainResult = [PSCustomObject]@{
        Id = 'plain'
        Label = 'Plain'
        AnswerBody = $plainAnswer
        SourcesBlock = $plainSources
        QuotesBlock = $plainQuotes
        RetrievalBody = $plainRetrieval
        Evaluation = $plainEvaluation
    }

    $modeResults = New-Object System.Collections.Generic.List[object]
    foreach ($mode in $ragModes) {
        $run = Invoke-CliCommand -WorkingDirectory $ProjectRoot -BatPath $cliBatPath -Arguments @('ask','--query',[string]$question.question,'--mode','rag','--strategy',$Strategy,'--top',"$TopK",'--post-mode',$mode.Id,'--show-all-candidates')
        if ($run.ExitCode -ne 0) {
            Throw-EvaluationFailure -Stage 'RAG evaluation' -CommandDescription "ask --query $($question.question) --mode rag --strategy $Strategy --top $TopK --post-mode $($mode.Id) --show-all-candidates" -Stdout $run.Stdout -Stderr $run.Stderr
        }

        $searchRun = Invoke-CliCommand -WorkingDirectory $ProjectRoot -BatPath $cliBatPath -Arguments @('search','--query',[string]$question.question,'--strategy',$Strategy,'--top',"$TopK",'--post-mode',$mode.Id,'--show-all-candidates')
        if ($searchRun.ExitCode -ne 0) {
            Throw-EvaluationFailure -Stage 'RAG retrieval evaluation' -CommandDescription "search --query $($question.question) --strategy $Strategy --top $TopK --post-mode $($mode.Id) --show-all-candidates" -Stdout $searchRun.Stdout -Stderr $searchRun.Stderr
        }

        $answer = Extract-AnswerBody -Output $run.Stdout
        $sources = Extract-SourcesBlock -Output $run.Stdout
        $quotes = Extract-QuotesBlock -Output $run.Stdout
        $retrieval = Extract-RetrievalBody -Output $searchRun.Stdout
        $evaluation = Evaluate-RunResult -ModeId $mode.Id -Answer $answer -SourcesBlock $sources -QuotesBlock $quotes -RetrievalBody $retrieval -ExpectedSources ([string[]]$question.expectedSources) -ExpectationTerms $expectationTerms

        $modeResults.Add([PSCustomObject]@{
            Id = $mode.Id
            Label = $mode.Label
            AnswerBody = $answer
            SourcesBlock = $sources
            QuotesBlock = $quotes
            RetrievalBody = $retrieval
            Evaluation = $evaluation
        })
    }

    $bestModeScore = (($modeResults | ForEach-Object { $_.Evaluation.Score }) | Measure-Object -Maximum).Maximum
    foreach ($bestMode in @($modeResults | Where-Object { $_.Evaluation.Score -eq $bestModeScore })) { $modeWinCounters[$bestMode.Id]++ }

    [void]$summarySections.Add((New-SummarySection -QuestionRecord $question -ModeResults $modeResults -PlainResult $plainResult))
    $combinedResults = @($plainResult) + @($modeResults | ForEach-Object { $_ })
    [void]$rawSections.Add((New-RawSection -Id ([int]$question.id) -Question ([string]$question.question) -Expectation ([string[]]$question.expectation) -ExpectedSources ([string[]]$question.expectedSources) -RunResults $combinedResults))
}

$summaryHeader = @(
    '# RAG model-rerank evaluation by question',
    '',
    'Auto-generated report for docs/articles/doroshevich.',
    '',
    'Run parameters:',
    "- input = $InputDir",
    "- strategy = $Strategy",
    "- topK = $TopK",
    '- post-modes = model-rerank',
    '',
    'Summary rubric:',
    '- success - the answer is grounded, has sources and quotes, and the quotes overlap with the answer',
    '- partial - some support exists, but one of the key signals is missing or weak',
    '- fail - support is weak, the output lacks structure, or the answer drifts away from the retrieval',
    '',
    'Mode wins:',
    ("- model-rerank: {0} wins" -f $modeWinCounters['model-rerank'])
) -join "`n"

$rawHeader = @(
    '# Raw evaluation by question',
    '',
    'Full run for each question and mode.',
    '',
    'Run parameters:',
    "- input = $InputDir",
    "- strategy = $Strategy",
    "- topK = $TopK",
    '- all RAG runs use --post-mode model-rerank --show-all-candidates',
    '- raw output splits each run into answer / sources / quotes / retrieval blocks'
) -join "`n"

$summaryReport = $summaryHeader + "`n`n" + ([string]::Join("`n`n", @($summarySections | ForEach-Object { [string]$_ })))
$rawReport = $rawHeader + "`n`n" + ([string]::Join("`n`n", @($rawSections | ForEach-Object { [string]$_ })))

foreach ($path in @($OutputPath, $RawOutputPath)) {
    $directory = Split-Path -Parent $path
    if ($directory) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
}

[System.IO.File]::WriteAllText($OutputPath, $summaryReport, [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($RawOutputPath, $rawReport, [System.Text.Encoding]::UTF8)
[Console]::Out.WriteLine("Evaluation summary written to: $OutputPath")
[Console]::Out.WriteLine("Evaluation raw report written to: $RawOutputPath")
