#!/usr/bin/env bash
# Survey: four surveys in the states a tenant meets them in: a bilingual NPS survey (published, with a public link and
# personal links), a scored quiz (published), a draft nobody published, and one that was closed. Question and option ids
# are fresh UUIDs; the translations carry every key in both locales, which is what a publish checks.
#
# Responses are not seeded: they arrive only through a respondent's public link, and `POST /survey/p/l/{token}/open`
# currently answers 500 on the local stack ("no request scope is bound"), so there is no way to submit one.
# Sourced by seed.sh.

# Replaces every __NAME__ placeholder with a UUID, the same one wherever a name repeats.
survey_ids() {
  local text names name
  text=$(cat)
  names=$(printf '%s' "${text}" | grep -o '__[A-Z0-9]*__' | sort -u)
  for name in ${names}; do
    text=${text//${name}/$(uuidgen | tr '[:upper:]' '[:lower:]')}
  done
  printf '%s' "${text}"
}

survey_nps_document() {
  survey_ids <<'JSON'
{"settings":{"default_locale":"en","locales":["en","vi"],"quiz":{"enabled":false,"return_score":false},"timezone":"Asia/Ho_Chi_Minh"},
 "pages":[{"id":"__P1__","questions":[
   {"id":"__Q1__","type":"nps","required":true},
   {"id":"__Q2__","type":"single_choice","required":false,"options":[
     {"id":"__O1__","value":"store"},{"id":"__O2__","value":"app"},{"id":"__O3__","value":"website"},{"id":"__O4__","value":"friend"}]},
   {"id":"__Q3__","type":"long_text","required":false,"visible_if":{"op":"lt","args":[{"answer":"__Q1__"},7]}}]}],
 "computed":[],"scoring":{"rules":[]},
 "translations":{
  "en":{"survey.title":"How did we do?","page.__P1__.title":"Your visit",
        "question.__Q1__.label":"How likely are you to recommend Acme Coffee to a friend?","question.__Q1__.help":"0 is not at all, 10 is extremely likely",
        "question.__Q2__.label":"How did you first hear about us?","question.__Q2__.help":"Pick one",
        "option.__O1__.label":"In a store","option.__O2__.label":"The app","option.__O3__.label":"Our website","option.__O4__.label":"A friend",
        "question.__Q3__.label":"What could we do better?","question.__Q3__.help":"Shown when your score is below 7"},
  "vi":{"survey.title":"Bạn thấy chúng tôi thế nào?","page.__P1__.title":"Lần ghé của bạn",
        "question.__Q1__.label":"Bạn có sẵn lòng giới thiệu Acme Coffee cho bạn bè không?","question.__Q1__.help":"0 là hoàn toàn không, 10 là chắc chắn",
        "question.__Q2__.label":"Bạn biết đến chúng tôi từ đâu?","question.__Q2__.help":"Chọn một",
        "option.__O1__.label":"Tại cửa hàng","option.__O2__.label":"Ứng dụng","option.__O3__.label":"Website","option.__O4__.label":"Bạn bè",
        "question.__Q3__.label":"Chúng tôi nên cải thiện điều gì?","question.__Q3__.help":"Hiện khi điểm của bạn dưới 7"}}}
JSON
}

survey_quiz_document() {
  survey_ids <<'JSON'
{"settings":{"default_locale":"en","locales":["en"],"quiz":{"enabled":true,"return_score":true},"response_cap":500},
 "pages":[{"id":"__P1__","questions":[
   {"id":"__Q1__","type":"single_choice","required":true,"options":[
     {"id":"__A1__","value":"light","score":1},{"id":"__A2__","value":"dark","score":0},{"id":"__A3__","value":"decaf","score":0}]},
   {"id":"__Q2__","type":"single_choice","required":true,"options":[
     {"id":"__B1__","value":"ethiopia","score":0},{"id":"__B2__","value":"colombia","score":0},{"id":"__B3__","value":"vietnam","score":2}]}]}],
 "computed":[],"scoring":{"rules":[]},
 "translations":{"en":{"survey.title":"Coffee knowledge quiz","page.__P1__.title":"Two questions",
   "question.__Q1__.label":"Which roast keeps the most acidity?","question.__Q1__.help":"One answer",
   "option.__A1__.label":"Light","option.__A2__.label":"Dark","option.__A3__.label":"Decaf",
   "question.__Q2__.label":"Which origin is our house robusta from?","question.__Q2__.help":"One answer",
   "option.__B1__.label":"Ethiopia","option.__B2__.label":"Colombia","option.__B3__.label":"Vietnam"}}}
JSON
}

survey_poll_document() {
  survey_ids <<'JSON'
{"settings":{"default_locale":"en","locales":["en"],"quiz":{"enabled":false,"return_score":false}},
 "pages":[{"id":"__P1__","questions":[
   {"id":"__Q1__","type":"single_choice","required":true,"options":[
     {"id":"__A1__","value":"peppermint"},{"id":"__A2__","value":"gingerbread"}]}]}],
 "computed":[],"scoring":{"rules":[]},
 "translations":{"en":{"survey.title":"Holiday latte vote","page.__P1__.title":"Vote",
   "question.__Q1__.label":"Which holiday latte should return?","question.__Q1__.help":"One answer",
   "option.__A1__.label":"Peppermint mocha","option.__A2__.label":"Gingerbread"}}}
JSON
}

# survey_ensure <name> <document json> <publish: yes|no> <then: none|close>   (id in SURVEY_ID)
survey_ensure() {
  local name="$1" document="$2" publish="$3" after="$4" etag
  acall_admin GET "/surveys?limit=100"
  SURVEY_ID=$(printf '%s' "${HTTP_BODY}" | jq -r --arg n "${name}" '(.data // []) | map(select(.name == $n))[0].id // empty')
  if [ -n "${SURVEY_ID}" ]; then
    info "survey ${name} exists"
    return 0
  fi
  acall_admin POST /surveys "$(jq -nc --arg n "${name}" '{name: $n}')"
  ok || { note "survey ${name}: ${HTTP_CODE} ${HTTP_BODY:0:140}"; return 1; }
  SURVEY_ID=$(pick '.id')
  etag=$(etag_of "${A_BASE}/surveys/${SURVEY_ID}/draft" "${A_CODE}.localhost" "${A_ADMIN}")
  acall_admin PUT "/surveys/${SURVEY_ID}/draft" "{\"document\":${document}}" "" "${etag}"
  ok || { note "survey ${name} draft: ${HTTP_CODE} ${HTTP_BODY:0:300}"; return 1; }
  [ "${publish}" = yes ] || return 0
  etag=$(etag_of "${A_BASE}/surveys/${SURVEY_ID}/draft" "${A_CODE}.localhost" "${A_ADMIN}")
  acall_admin POST "/surveys/${SURVEY_ID}/publish" "" "" "${etag}"
  ok || { note "survey ${name} publish: ${HTTP_CODE} ${HTTP_BODY:0:300}"; return 1; }
  [ "${after}" != close ] || acall_try "close ${name}" POST "/surveys/${SURVEY_ID}/close" || true
}

seed_survey() {
  step "Survey: acme"
  use_app acme survey "${PROXY}/survey/system/v1"
  local nps
  survey_ensure "Customer satisfaction (NPS)" "$(survey_nps_document)" yes none && nps="${SURVEY_ID}"
  if [ -n "${nps}" ]; then
    # a public link per channel, and personal links (one per customer) for the email campaign
    acall GET "/surveys/${nps}/links?limit=100"
    if [ "$(pick '.data | length')" = "0" ] || [ -z "$(pick '.data | length')" ]; then
      acall_try "public link store" POST "/surveys/${nps}/links" '{"source":"store-qr"}' || true
      acall_try "public link email" POST "/surveys/${nps}/links" '{"source":"email"}' || true
      acall_try "personal links" POST "/surveys/${nps}/links/personal" \
        '{"source":"campaign","links":[{"externalRef":"C-001"},{"externalRef":"C-002"},{"externalRef":"C-006"}]}' || true
    fi
  fi
  survey_ensure "Coffee knowledge quiz" "$(survey_quiz_document)" yes none || true
  survey_ensure "Staff pulse (draft)" "$(survey_poll_document)" no none || true
  survey_ensure "Holiday latte vote (closed)" "$(survey_poll_document)" yes close || true
}
