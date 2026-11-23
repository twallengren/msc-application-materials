# Report build record

**Verified: 14 A4 pages**, compiled with `latexmk -pdf` and BibTeX, then rendered and inspected on 23 November 2026. The final build has no undefined references, missing citations or overfull boxes.

The report covers the two solver designs, three extensions, the twelve-stage ladder, and measurements in three layers (one NPV evaluation, preparation, complete solve) with a gap-order experiment, thread scaling and an accuracy comparison. It uses 262 full benchmark configurations and 10,752 diagnostic rows. The author's section (`author-statement.tex`) still holds placeholder prompts; the page count will change when it is written, and the limit asserted by `render_report.py` is 15.

## Toolchain

The starting environment had no TeX compiler on `PATH`. A portable [TinyTeX v2026.11](https://github.com/rstudio/tinytex-releases/releases/tag/v2026.11) distribution was unpacked under `/tmp/irr-tex`, without a system-wide installation. The downloaded `TinyTeX-1-darwin-v2026.11.tar.xz` SHA-256 was `9416a257c4caea2f82351fa2f89252b8bd75dceeb0ce6e442d25530b1922a02d`. The missing `listings` and `microtype` packages were installed. Tool and package versions are in [tex-environment.txt](../results/tex-environment.txt); the build output is in [report-build.log](../results/report-build.log).

## Commands

From `projects/irr-java`, with a normal TeX installation:

```sh
.venv/bin/python scripts/verify_evidence.py
.venv/bin/python scripts/analyze.py
cd report
latexmk -pdf -interaction=nonstopmode -halt-on-error report.tex
```

The invocation used in this environment:

```sh
cd report
PATH=/tmp/irr-tex/TinyTeX/bin/universal-darwin:$PATH \
  latexmk -pdf -interaction=nonstopmode -halt-on-error report.tex
cd ..
.venv/bin/python -m pip install -r scripts/review-requirements.txt
.venv/bin/python scripts/render_report.py
```

`render_report.py` asserts the 10–15-page range and writes page previews and extracted text to `/tmp/irr-report-review`. The temporary compiler is not part of the repository.
