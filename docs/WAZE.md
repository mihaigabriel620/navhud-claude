# Can we use the Waze database?

Asked in August 2026, researched properly, answered: **no — and not for the
reason you would expect.**

## The two things Waze calls a camera

This is the part that decides the question.

- **Fixed cameras** are persistent map objects, curated in the Waze Map Editor.
  Since September 2024 they live in Waze's "permanent hazards" framework.
  Waze's own help says they are added *"based on reports from our partners and
  map editors"* — twelve types, from speed and red-light to bus lane and seat
  belt.
- **Police and mobile speed traps** are transient user reports that expire.
  Waze's editing guide is explicit that portable cameras are
  *"temporary and not mapped in Waze"* and should be reported in the app as
  police instead.

## What each route gives you

| Data | Official partner feed | georss / resellers | Waze Map Editor |
|---|---|---|---|
| Jams, accidents, hazards, closures | yes | yes | — |
| Police reports (transient) | **no** | yes | — |
| Mobile speed camera reports | yes, as a hazard subtype | yes | — |
| **Fixed speed cameras** | **no** | **no** | editor only, login-gated |

**No API — official or unofficial — exposes fixed camera positions by area.**
Every "Waze API" product resells the same `live-map/api/georss` alerts feed,
which is transient reports and nothing else.

## The two links, specifically

- [`JMoore335/waze_traffic_api`](https://github.com/JMoore335/waze_traffic_api) —
  17 stars, 4 commits, all on 28 September 2016. It does not talk to Waze at
  all: it polls `localhost:8080` and tells you to download a
  [2015 jar](https://github.com/Nimrod007/waze-api) that has an unanswered
  ["403 forbidden"](https://github.com/Nimrod007/waze-api/issues/18) issue open
  since 2018. No licence file, so not legally reusable either. Its own README
  says it logs *"police sightings"* — no cameras.
- [OpenWeb Ninja "Waze API"](https://www.openwebninja.com/api/waze) — a
  self-declared scraper: *"Leverage our advanced scraping technology &
  infrastructure."* The word "camera" appears zero times on the page; the data
  is jams, accidents, police and hazards. Free tier is 100 requests/month, then
  $25 / $75 / $150. Their [terms](https://www.openwebninja.com/terms) never
  mention Waze, cap liability at one month's fee, and make **you** indemnify
  **them** for *"violation of the rights of a third party, including but not
  limited to intellectual property rights."*

## The official doors, all closed

- **Waze for Cities / Partner Hub** — *"You can apply for Waze for Cities if you
  represent a government agency or a private road operator."* And the feed's
  alert-type table has no `POLICE` type at all.
- **Waze Transport SDK** — *"The SDK is not for partners to build their own
  navigation app,"* and under unsupported features, verbatim: *"The SDK does
  not support ... server-side access to Waze data, like traffic reports and
  driver speed."*
- **Deep links** — one-directional. They open Waze; nothing comes back.

## The terms, and the robots file

Waze's [Terms of Use](https://support.google.com/waze/answer/12373727) license
the service *"for your personal, non-commercial purposes"* and then prohibit,
among other things, *"using automated means to access Content,"* *"creating a
database by systematically downloading and storing Content,"* and
*"integrating the Service or its Content with, or using the Service or its
Content to augment, another product or service, without Waze's prior written
consent."* That last clause covers a hobby app with no money in it.

`https://www.waze.com/robots.txt` disallows the whole `/live-map/api` tree with
a single carve-out for `/live-map/api/venues`. A test request to
`/live-map/api/georss` from this environment returned **403**, while
`https://www.waze.com/live-map` returned 200 — the API path is gated in a way
the page is not.

## Conclusion

Even setting the terms aside entirely, the scraped feed **does not contain the
data we want.** We would be taking on legal exposure and a dependency on
somebody's proxy pool in exchange for police reports, which is the one Waze
feature explicitly not wanted here.
