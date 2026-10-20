# Third-party notices

Pentaho Live (the `pentaho-mcp` plugin for PDI / Spoon) is MIT-licensed (see `LICENSE`). The plugin
redistributes the third-party software listed here. Everything else it uses at run time (Kettle, SWT,
Jackson, xmlbeans, commons-*, Batik...) is part of Pentaho Data Integration itself, lives in PDI's own `lib\`
folder and is **not** redistributed by this plugin.

| Component | Version | License | Where it ships |
|---|---|---|---|
| Mermaid (with the libraries bundled into its browser build, listed below) | 11.4.1 | MIT | `mermaid.min.js` inside the plugin jar (`io/github/ivi2511/pentahomcp/informes/vendor/`); inlined into every interactive HTML report |
| Apache POI (`poi`, `poi-ooxml`, `poi-ooxml-schemas`) | 4.1.1 | Apache-2.0 | `pentaho-mcp\lib\`, copied unchanged from PDI's `plugins\pdi-core-plugins\lib\` (each jar keeps its own `META-INF/LICENSE` and `META-INF/NOTICE`) |

## 1. Audit of the Mermaid bundle (done for 0.5.0)

- **File.** `mermaid.min.js` is byte-identical to `dist/mermaid.min.js` of the npm package `mermaid@11.4.1`
  (SHA-256 `a43bc1afd446f9c4cc66ac5dd45d02e8d65e26fc5344ec0ef787f88d6ddb6f9e`, checked against the jsDelivr
  file hash). It is the self-contained IIFE build: every runtime dependency is compiled into it.
- **Method.** The full production dependency tree of `mermaid@11.4.1` was resolved from the npm registry,
  taking for each range the newest version published before Mermaid 11.4.1 (2024-11-27), and each package's
  published license file was read. The bundle's own legal comments add `js-yaml` 4.1.0 (bundled from the
  Mermaid monorepo, not listed as a dependency) and confirm the versions of DOMPurify 3.2.1, lodash-es, cytoscape
  and KaTeX 0.16.11 (version string). Packages that cannot be in a browser build (TypeScript types, command-line
  tools, Node-only file loaders) are marked as such; their distinctive strings do not occur in the bundle.
- **Result: 132 packages** (MIT: 85, ISC: 33, Apache-2.0: 6, BSD-3-Clause: 6, (MPL-2.0 OR Apache-2.0): 1, Unlicense: 1). Nothing is GPL, LGPL, EPL, CDDL or otherwise copyleft.

Points that are not plain MIT / Apache-2.0 / ISC / BSD, for the record:

| Package | License | Decision |
|---|---|---|
| d3 7.9.0 and its d3-* modules, internmap, delaunator | ISC | Accepted: ISC is a permissive license functionally equivalent to MIT. Mermaid does not work without d3. |
| dompurify 3.2.1 | MPL-2.0 OR Apache-2.0 | Used under **Apache-2.0** (the licensee may choose). |
| robust-predicates 3.0.2 (d3 → d3-delaunay → delaunator) | Unlicense | Public-domain dedication, no conditions. Mermaid never uses Delaunay/Voronoi code, so it is most likely tree-shaken out of the bundle; that cannot be proven from the minified file, so it is listed. |
| elkjs | EPL-2.0 | **Not included.** Mermaid 11 moved the ELK layout to the separate package `@mermaid-js/layout-elk`, which is not a dependency; the bundle contains no `org.eclipse.elk` code (its few "elk" strings are configuration names and the "moved to an external package" warning). |
| argparse 2.0.1 (dependency of js-yaml) | Python-2.0 | Not included: it is only used by the `js-yaml` command-line tool; the bundled `dist/js-yaml.mjs` does not import it. |
| Apache POI's `LICENSE` mentions EPL-1.0 (JUnit, JaCoCo) and Bouncy Castle | – | Those are POI test-time / optional dependencies and are **not** in the three POI jars that ship. |

The interactive HTML inlines the bundle with every `://` written as `:\/\/`, which is the same string in
JavaScript; it only appears in error messages, license comments and XML namespaces. The report never loads
anything from the network.

## 2. Dependency tree of `mermaid@11.4.1`

| Package | Version | License | In the browser bundle | Copyright |
|---|---|---|---|---|
| @antfu/install-pkg | 0.4.1 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2021 Anthony Fu &lt;https://github.com/antfu&gt; |
| @antfu/utils | 0.7.10 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2021 Anthony Fu &lt;https://github.com/antfu&gt; |
| @braintree/sanitize-url | 7.1.0 | MIT | yes | Copyright (c) 2017 Braintree |
| @chevrotain/cst-dts-gen | 11.0.3 | Apache-2.0 | yes | (no copyright line in the license file) |
| @chevrotain/gast | 11.0.3 | Apache-2.0 | yes | (no copyright line in the license file) |
| @chevrotain/regexp-to-ast | 11.0.3 | Apache-2.0 | yes | (no copyright line in the license file) |
| @chevrotain/types | 11.0.3 | Apache-2.0 | yes | (no copyright line in the license file; npm author: Shahar Soel) |
| @chevrotain/utils | 11.0.3 | Apache-2.0 | yes | (no copyright line in the license file; npm author: Shahar Soel) |
| @iconify/types | 2.0.0 | MIT | yes | Copyright (c) 2021 - 2022 Vjacheslav Trushkin / Iconify OÜ |
| @iconify/utils | 2.1.33 | MIT | yes | Copyright (c) 2021-PRESENT Vjacheslav Trushkin |
| @mermaid-js/parser | 0.3.0 | MIT | yes | Copyright (c) 2023 Yokozuna59 |
| @types/d3 | 7.4.3 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-array | 3.2.1 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-axis | 3.0.6 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-brush | 3.0.6 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-chord | 3.0.6 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-color | 3.1.3 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-contour | 3.0.6 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-delaunay | 6.0.4 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-dispatch | 3.0.6 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-drag | 3.0.7 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-dsv | 3.0.7 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-ease | 3.0.2 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-fetch | 3.0.7 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-force | 3.0.10 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-format | 3.0.4 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-geo | 3.1.0 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-hierarchy | 3.1.7 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-interpolate | 3.0.4 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-path | 3.1.0 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-polygon | 3.0.2 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-quadtree | 3.0.6 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-random | 3.0.3 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-scale | 4.0.8 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-scale-chromatic | 3.0.3 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-selection | 3.0.11 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-shape | 3.1.6 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-time | 3.0.4 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-time-format | 4.0.3 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-timer | 3.0.2 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-transition | 3.0.9 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/d3-zoom | 3.0.8 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| @types/geojson | 7946.0.14 | MIT | no (TypeScript types only) | Copyright (c) Microsoft Corporation. |
| acorn | 8.14.0 | MIT | no (Node-only loader of @iconify/utils) | Copyright (C) 2012-2022 by various contributors (see AUTHORS) |
| chevrotain | 11.0.3 | Apache-2.0 | yes | (no copyright line in the license file; npm author: Shahar Soel) |
| chevrotain-allstar | 0.3.1 | MIT | yes | Copyright 2022 TypeFox GmbH |
| commander | 7.2.0 | MIT | no (command-line tool of katex / d3-dsv) | Copyright (c) 2011 TJ Holowaychuk &lt;tj@vision-media.ca&gt; |
| commander | 8.3.0 | MIT | no (command-line tool of katex / d3-dsv) | Copyright (c) 2011 TJ Holowaychuk &lt;tj@vision-media.ca&gt; |
| confbox | 0.1.8 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) Pooya Parsa &lt;pooya@pi0.io&gt;; Copyright (C) 2011-2015 by Vitaly Puzrin; Copyright (c) Squirrel Chat et al., All rights reserved.; Copyright (c) Microsoft; Copyright (c) 2012-2018 Aseem Kishore, and others (https://github.com/json5/json5/graphs/contributors); Copyright (c) Sindre Sorhus &lt;sindresorhus@gmail.com&gt; (https://sindresorhus.com) |
| cose-base | 1.0.3 | MIT | yes | Copyright (c) 2019 - present, iVis@Bilkent. |
| cose-base | 2.2.0 | MIT | yes | Copyright (c) 2019 - present, iVis@Bilkent. |
| cytoscape | 3.30.4 | MIT | yes | Copyright (c) 2016-2024, The Cytoscape Consortium. |
| cytoscape-cose-bilkent | 4.1.0 | MIT | yes | Copyright (c) 2016-2018, The Cytoscape Consortium. |
| cytoscape-fcose | 2.2.0 | MIT | yes | Copyright (c) 2018 - present, iVis-at-Bilkent. |
| d3 | 7.9.0 | ISC | yes | Copyright 2010-2023 Mike Bostock |
| d3-array | 2.12.1 | BSD-3-Clause | yes | Copyright 2010-2020 Mike Bostock |
| d3-array | 3.2.4 | ISC | yes | Copyright 2010-2023 Mike Bostock |
| d3-axis | 3.0.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-brush | 3.0.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-chord | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-color | 3.1.0 | ISC | yes | Copyright 2010-2022 Mike Bostock |
| d3-contour | 4.0.2 | ISC | yes | Copyright 2012-2023 Mike Bostock |
| d3-delaunay | 6.0.4 | ISC | unclear (Delaunay code not used by Mermaid; likely tree-shaken) | Copyright 2018-2021 Observable, Inc.; Copyright 2021 Mapbox |
| d3-dispatch | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-drag | 3.0.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-dsv | 3.0.1 | ISC | yes | Copyright 2013-2021 Mike Bostock |
| d3-ease | 3.0.1 | BSD-3-Clause | yes | Copyright 2010-2021 Mike Bostock; Copyright 2001 Robert Penner |
| d3-fetch | 3.0.1 | ISC | yes | Copyright 2016-2021 Mike Bostock |
| d3-force | 3.0.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-format | 3.1.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-geo | 3.1.1 | ISC | yes | Copyright 2010-2024 Mike Bostock; Copyright 2008-2012 Charles Karney |
| d3-hierarchy | 3.1.2 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-interpolate | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-path | 1.0.9 | BSD-3-Clause | yes | Copyright 2015-2016 Mike Bostock |
| d3-path | 3.1.0 | ISC | yes | Copyright 2015-2022 Mike Bostock |
| d3-polygon | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-quadtree | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-random | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-sankey | 0.12.3 | BSD-3-Clause | yes | Copyright 2015, Mike Bostock |
| d3-scale | 4.0.2 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-scale-chromatic | 3.1.0 | ISC | yes | Copyright 2010-2024 Mike Bostock; Copyright 2002 Cynthia Brewer, Mark Harrower, and The Pennsylvania State University |
| d3-selection | 3.0.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-shape | 1.3.7 | BSD-3-Clause | yes | Copyright 2010-2015 Mike Bostock |
| d3-shape | 3.2.0 | ISC | yes | Copyright 2010-2022 Mike Bostock |
| d3-time | 3.1.0 | ISC | yes | Copyright 2010-2022 Mike Bostock |
| d3-time-format | 4.1.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-timer | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-transition | 3.0.1 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| d3-zoom | 3.0.0 | ISC | yes | Copyright 2010-2021 Mike Bostock |
| dagre-d3-es | 7.0.11 | MIT | yes | Original dagre-d3 copyright: Copyright (c) 2013 Chris Pettitt; Original dagre copyright: Copyright (c) 2012-2014 Chris Pettitt; Original graphlib copyright: Copyright (c) 2012-2014 Chris Pettitt; Copyright (c) 2022-2024 Thibaut Lassalle, David Newell, Alois Klink, Sidharth Vinod and dagre-es contributors |
| dayjs | 1.11.13 | MIT | yes | Copyright (c) 2018-present, iamkun |
| debug | 4.3.7 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2014-2017 TJ Holowaychuk &lt;tj@vision-media.ca&gt;; Copyright (c) 2018-2021 Josh Junon |
| delaunator | 5.0.1 | ISC | unclear (Delaunay code not used by Mermaid; likely tree-shaken) | Copyright (c) 2021, Mapbox |
| dompurify | 3.2.1 | (MPL-2.0 OR Apache-2.0) | yes | Copyright 2024 Dr.-Ing. Mario Heiderich, Cure53 |
| hachure-fill | 0.5.2 | MIT | yes | Copyright (c) 2023 Preet Shihn |
| iconv-lite | 0.6.3 | MIT | no (command-line tool of katex / d3-dsv) | Copyright (c) 2011 Alexander Shtuchkin |
| internmap | 1.0.1 | ISC | yes | Copyright 2021 Mike Bostock |
| internmap | 2.0.3 | ISC | yes | Copyright 2021 Mike Bostock |
| js-yaml | 4.1.0 | MIT | yes | Copyright (C) 2011-2015 by Vitaly Puzrin |
| katex | 0.16.11 | MIT | yes | Copyright (c) 2013-2020 Khan Academy and other contributors |
| khroma | 2.1.0 | MIT | yes | Copyright (c) 2019-present Fabio Spampinato, Andrew Maney |
| kolorist | 1.8.0 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2020-present Marvin Hagemeister |
| langium | 3.0.0 | MIT | yes | Copyright 2021 TypeFox GmbH |
| layout-base | 1.0.2 | MIT | yes | Copyright (c) 2019 iVis@Bilkent |
| layout-base | 2.0.1 | MIT | yes | Copyright (c) 2019 iVis@Bilkent |
| local-pkg | 0.5.1 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2021 Anthony Fu &lt;https://github.com/antfu&gt; |
| lodash-es | 4.17.21 | MIT | yes | Copyright OpenJS Foundation and other contributors &lt;https://openjsf.org/&gt;; Based on Underscore.js, copyright Jeremy Ashkenas, |
| marked | 13.0.3 | MIT | yes | Copyright (c) 2018+, MarkedJS (https://github.com/markedjs/); Copyright (c) 2011-2018, Christopher Jeffrey (https://github.com/chjj/); Copyright © 2004, John Gruber |
| mermaid | 11.4.1 | MIT | yes | Copyright (c) 2014 - 2022 Knut Sveidqvist |
| mlly | 1.7.3 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) Pooya Parsa &lt;pooya@pi0.io&gt; |
| ms | 2.1.3 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2020 Vercel, Inc. |
| package-manager-detector | 0.2.5 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2020-PRESENT Anthony Fu &lt;https://github.com/antfu&gt; |
| path-data-parser | 0.1.0 | MIT | yes | Copyright (c) 2020 Preet Shihn |
| pathe | 1.1.2 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) Pooya Parsa &lt;pooya@pi0.io&gt; - Daniel Roe &lt;daniel@roe.dev&gt;; Copyright Joyent, Inc. and other Node contributors. |
| pkg-types | 1.2.1 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) Pooya Parsa &lt;pooya@pi0.io&gt; - Daniel Roe &lt;daniel@roe.dev&gt;; Copyright Joyent, Inc. and other Node contributors. |
| points-on-curve | 0.2.0 | MIT | yes | Copyright (c) 2020 Preet Shihn |
| points-on-path | 0.2.1 | MIT | yes | Copyright (c) 2020 Preet |
| robust-predicates | 3.0.2 | Unlicense | unclear (Delaunay code not used by Mermaid; likely tree-shaken) | (no copyright line in the license file; npm author: Vladimir Agafonkin) |
| roughjs | 4.6.6 | MIT | yes | Copyright (c) 2019 Preet Shihn |
| rw | 1.3.3 | BSD-3-Clause | no (command-line tool of katex / d3-dsv) | Copyright (c) 2014-2016, Michael Bostock |
| safer-buffer | 2.1.2 | MIT | no (command-line tool of katex / d3-dsv) | Copyright (c) 2018 Nikita Skovoroda &lt;chalkerx@gmail.com&gt; |
| stylis | 4.3.4 | MIT | yes | Copyright (c) 2016-present Sultan Tarimo |
| tinyexec | 0.3.1 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) 2024 Tinylibs |
| ts-dedent | 2.2.0 | MIT | yes | Copyright (c) 2018 Tamino Martinius |
| ufo | 1.5.4 | MIT | no (Node-only loader of @iconify/utils) | Copyright (c) Pooya Parsa &lt;pooya@pi0.io&gt; |
| uuid | 9.0.1 | MIT | yes | Copyright (c) 2010-2020 Robert Kieffer and other contributors |
| vscode-jsonrpc | 8.2.0 | MIT | yes | Copyright (c) Microsoft Corporation |
| vscode-languageserver | 9.0.1 | MIT | yes | Copyright (c) Microsoft Corporation |
| vscode-languageserver-protocol | 3.17.5 | MIT | yes | Copyright (c) Microsoft Corporation |
| vscode-languageserver-textdocument | 1.0.12 | MIT | yes | Copyright (c) Microsoft Corporation |
| vscode-languageserver-types | 3.17.5 | MIT | yes | Copyright (c) Microsoft Corporation |
| vscode-uri | 3.0.8 | MIT | yes | Copyright (c) Microsoft |

## 3. Apache POI 4.1.1 NOTICE

```
Apache POI
Copyright 2003-2019 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).

This product contains parts that were originally based on software from BEA.
Copyright (c) 2000-2003, BEA Systems, <http://www.bea.com/> (dead link),
which was acquired by Oracle Corporation in 2008.
<http://www.oracle.com/us/corporate/Acquisitions/bea/index.html>
<https://en.wikipedia.org/wiki/BEA_Systems>

This product contains W3C XML Schema documents. Copyright 2001-2003 (c)
World Wide Web Consortium (Massachusetts Institute of Technology, European
Research Consortium for Informatics and Mathematics, Keio University)

This product contains the chunks_parse_cmds.tbl file from the vsdump program.
Copyright (C) 2006-2007 Valek Filippov (frob@df.ru)

This product contains parts of the eID Applet project 
<http://eid-applet.googlecode.com> and <https://github.com/e-Contract/eid-applet>.
Copyright (c) 2009-2014
FedICT (federal ICT department of Belgium), e-Contract.be BVBA (https://www.e-contract.be),
Bart Hanssens from FedICT
```

## 4. License texts

### MIT License

Applies to Mermaid and to every package marked MIT above, each under its own copyright line. Text as
distributed with Mermaid:

```
The MIT License (MIT)

Copyright (c) 2014 - 2022 Knut Sveidqvist

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### ISC License

Applies to the packages marked ISC, each under its own copyright line. Text as distributed with d3:

```
Copyright 2010-2023 Mike Bostock

Permission to use, copy, modify, and/or distribute this software for any purpose
with or without fee is hereby granted, provided that the above copyright notice
and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
THIS SOFTWARE.
```

### BSD 3-Clause License

Applies to d3-sankey 0.12.3, d3-array 2.12.1, d3-shape 1.3.7, d3-path 1.0.9, d3-ease 3.0.1 (which also
carries Robert Penner's easing-equations notice) and rw 1.3.3. Text as distributed with d3-ease:

```
Copyright 2010-2021 Mike Bostock
Copyright 2001 Robert Penner
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the author nor the names of contributors may be used to
  endorse or promote products derived from this software without specific prior
  written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### The Unlicense (robust-predicates)

```
This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or
distribute this software, either in source code form or as a compiled
binary, for any purpose, commercial or non-commercial, and by any
means.

In jurisdictions that recognize copyright laws, the author or authors
of this software dedicate any and all copyright interest in the
software to the public domain. We make this dedication for the benefit
of the public at large and to the detriment of our heirs and
successors. We intend this dedication to be an overt act of
relinquishment in perpetuity of all present and future rights to this
software under copyright law.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

For more information, please refer to <http://unlicense.org>
```

### Apache License 2.0

Applies to Apache POI 4.1.1, to Chevrotain 11.0.3 and its `@chevrotain/*` modules, and to DOMPurify 3.2.1
(used under its Apache-2.0 option).

```
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```
