# The Midnight Brewer — the book

LaTeX source for *Building a Contactless Payment Terminal From Scratch*: a
~175-page book that rebuilds this entire project from an empty folder.

## Build

```bash
make            # full build, resolves cross-references + index
make quick      # one fast pass while drafting
make watch      # rebuild automatically on save
make open       # build and open (macOS)
make clean      # remove aux files, keep the PDF
```

Needs a TeX Live install with `tcolorbox`, `listings`, `tikz`, `libertinus`,
`FiraSans` and `sourcecodepro` — all present in TeX Live full. No shell-escape,
no external tools, no `minted`.

## Layout

```
brewer-book.tex        main file: title page, structure, \input order
preamble.tex           the design system — fonts, colours, boxes, code styles
frontmatter/preface.tex
chapters/00-setup.tex … 13-deploy.tex
appendices/A-test-vectors.tex, B-troubleshooting.tex, C-video-plan.tex
```

## Writing in it

Four callout environments, each with a coloured margin rule:

```latex
\begin{concept}{Title}  ... \end{concept}   % blue   — the OOP/design idea
\begin{analogy}{Title}  ... \end{analogy}   % amber  — the non-programming parallel
\begin{trap}{Title}     ... \end{trap}      % red    — a bug that has actually bitten
\begin{deeper}{Title}   ... \end{deeper}    % grey   — optional depth
\begin{verifybox}       ... \end{verifybox} % green  — the "do not continue until" gate
```

Code environments (syntax-highlighted, page-breakable):

```latex
\begin{jcode} ... \end{jcode}    % Java
\begin{dcode} ... \end{dcode}    % Dart
\begin{shell} ... \end{shell}    % shell
\begin{xcode} ... \end{xcode}    % XML / YAML
\begin{trace} ... \end{trace}    % hex dumps, terminal output
```

Inline: `\code{...}`, `\hex{...}`, `\reg{...}` (register names).

Each chapter opens with:

```latex
\chapterbrief{goal}{concept introduced}{what you end with}
```

Symptom→cause tables use `\begin{traptable} ... \end{traptable}` (rows are
`symptom & cause \\`).

## Cross-references

Chapters carry `\label{ch:spi}`, `ch:driver`, `ch:reader`, `ch:blocks`,
`ch:activation`, `ch:apdu`, `ch:cmac`, `ch:sesskeys`, `ch:auth`, `ch:secmsg`,
`ch:kiosk`, `ch:android`, `ch:deploy`; appendices `app:vectors`, `app:trouble`,
`app:video`.

Reference them as `Chapter~\ref{ch:auth}` so renumbering never breaks the text.

## Relation to `docs/cookbook/`

`docs/cookbook/` is the same material in Markdown — better for reading on GitHub
or grepping while coding. This is the print/PDF edition: diagrams, index,
cross-references, and a design that survives being read cover to cover.

Both are kept in sync by hand; the LaTeX version is the more detailed one.
