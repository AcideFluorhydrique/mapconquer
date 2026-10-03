<!--
SPDX-FileCopyrightText: 2026 AcideFluorhydrique
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Geographic extracts

The `theatre_*.json` files here are cropped extracts of
[Natural Earth](https://www.naturalearthdata.com) vector data: the land polygons
(1:50m) and river centrelines (1:10m) that fall inside each theatre map's bounds.

Natural Earth is in the **public domain**: it may be used, modified and
redistributed for any purpose without permission or attribution. It is credited
here anyway.

The files are produced by `python3 tools/naturalearth.py <directory>`, where the
directory holds the downloaded GeoJSON sources named at the top of that script.
`tools/genworld.py` only reads the extracts, so regenerating the maps needs
neither the sources nor a network connection.
