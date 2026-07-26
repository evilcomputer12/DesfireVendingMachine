# The Midnight Brewer -- book build
#
#   make          build the PDF (resolves refs + index automatically)
#   make quick    one fast pdflatex pass, for checking a single edit
#   make clean    remove build artefacts, keep the PDF
#   make purge    remove everything including the PDF
#   make open     build and open (macOS)

MAIN    = brewer-book
SOURCES = $(MAIN).tex preamble.tex \
          $(wildcard frontmatter/*.tex) \
          $(wildcard chapters/*.tex) \
          $(wildcard appendices/*.tex)

.PHONY: all quick clean purge open watch

all: $(MAIN).pdf

# latexmk reruns pdflatex and makeindex until cross-references settle.
$(MAIN).pdf: $(SOURCES)
	latexmk -pdf -interaction=nonstopmode -halt-on-error $(MAIN).tex

# One pass. Page numbers and refs may be stale; fine while drafting.
quick:
	pdflatex -interaction=nonstopmode $(MAIN).tex | \
	  grep -E "^!|Output written" || true

# Rebuild automatically whenever a source file changes.
watch:
	latexmk -pdf -pvc -interaction=nonstopmode $(MAIN).tex

clean:
	latexmk -c $(MAIN).tex
	rm -f $(MAIN).ind $(MAIN).idx $(MAIN).ilg $(MAIN).bbl $(MAIN).run.xml

purge: clean
	rm -f $(MAIN).pdf

open: all
	open $(MAIN).pdf
