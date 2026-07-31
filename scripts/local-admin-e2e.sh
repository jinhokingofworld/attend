#!/usr/bin/env bash
# 실제 HTTP 세션으로 시스템 관리자와 부서 관리자 화면의 권한·렌더링을 검증한다.

set -euo pipefail
umask 077

base_url="${LOCAL_DEMO_BASE_URL:-http://127.0.0.1:8080}"
temporary_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${temporary_dir}"
}
trap cleanup EXIT

assert_contains() {
  local file_path="$1"
  local expected="$2"
  if ! grep --fixed-strings --quiet "${expected}" "${file_path}"; then
    echo "Expected text was not rendered: ${expected}" >&2
    exit 1
  fi
}

assert_not_contains() {
  local file_path="$1"
  local unexpected="$2"
  if grep --fixed-strings --quiet "${unexpected}" "${file_path}"; then
    echo "Sensitive or unexpected text was rendered: ${unexpected}" >&2
    exit 1
  fi
}

login() {
  local username="$1"
  local password="$2"
  local cookie_file="$3"
  local prefix="$4"
  local login_page="${temporary_dir}/${prefix}-login.html"
  local headers="${temporary_dir}/${prefix}-login.headers"
  local csrf_token
  local status

  status="$(curl --silent --show-error \
    --cookie-jar "${cookie_file}" \
    --output "${login_page}" \
    --write-out '%{http_code}' \
    "${base_url}/login")"
  test "${status}" = "200"
  assert_contains "${login_page}" "Attend 관리자"

  csrf_token="$(sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' \
    "${login_page}" | head -n 1)"
  if [[ -z "${csrf_token}" ]]; then
    echo "Login CSRF token was not rendered." >&2
    exit 1
  fi

  status="$(curl --silent --show-error \
    --cookie "${cookie_file}" \
    --cookie-jar "${cookie_file}" \
    --dump-header "${headers}" \
    --output /dev/null \
    --write-out '%{http_code}' \
    --data-urlencode "usernameInput=${username}" \
    --data-urlencode "passwordInput=${password}" \
    --data-urlencode "_csrf=${csrf_token}" \
    "${base_url}/authentication")"
  test "${status}" = "302"
  grep --extended-regexp --ignore-case --quiet '^location: .*/admin' "${headers}"
}

system_cookie="${temporary_dir}/system.cookies"
department_cookie="${temporary_dir}/department.cookies"
system_home="${temporary_dir}/system-home.html"
department_list="${temporary_dir}/system-departments.html"
department_workspaces="${temporary_dir}/department-workspaces.html"
department_home="${temporary_dir}/department-home.html"
page="${temporary_dir}/page.html"

login \
  "local-system-admin" \
  "local-system-admin-2026" \
  "${system_cookie}" \
  "system"

curl --fail --silent --show-error --location \
  --cookie "${system_cookie}" --output "${system_home}" "${base_url}/admin"
assert_contains "${system_home}" "시스템 관리"
assert_contains "${system_home}" "공통 관리자 메뉴"

curl --fail --silent --show-error \
  --cookie "${system_cookie}" --output "${department_list}" \
  "${base_url}/admin/system/departments"
department_id="$(sed -n 's#.*href="/admin/system/departments/\([0-9][0-9]*\)".*#\1#p' \
  "${department_list}" | head -n 1)"
if [[ -z "${department_id}" ]]; then
  echo "Seeded department link was not rendered." >&2
  exit 1
fi

status="$(curl --silent --show-error --cookie "${system_cookie}" \
  --output /dev/null --write-out '%{http_code}' \
  "${base_url}/admin/departments/${department_id}")"
test "${status}" = "403"

login \
  "local-department-admin" \
  "local-department-admin-2026" \
  "${department_cookie}" \
  "department"

curl --fail --silent --show-error --location \
  --cookie "${department_cookie}" --output "${department_workspaces}" \
  "${base_url}/admin"
assert_contains "${department_workspaces}" "작업 공간 선택"

department_id="$(sed -n 's#.*href="/admin/departments/\([0-9][0-9]*\)".*#\1#p' \
  "${department_workspaces}" | head -n 1)"
if [[ -z "${department_id}" ]]; then
  echo "Department workspace link was not rendered." >&2
  exit 1
fi

status="$(curl --silent --show-error --cookie "${department_cookie}" \
  --output /dev/null --write-out '%{http_code}' \
  "${base_url}/admin/system")"
test "${status}" = "403"

curl --fail --silent --show-error --cookie "${department_cookie}" \
  --output "${department_home}" \
  "${base_url}/admin/departments/${department_id}"
assert_contains "${department_home}" "오늘의 출석"
assert_contains "${department_home}" 'data-count="present_count"'
assert_contains "${department_home}" '>1명</strong>'
assert_contains "${department_home}" "metric-list"

curl --fail --silent --show-error --cookie "${department_cookie}" \
  --output "${page}" \
  "${base_url}/admin/departments/${department_id}/dashboard-data"
assert_contains "${page}" '"present_count":1'
assert_contains "${page}" '"rows"'

for route_and_text in \
  "teachers|교사 추가" \
  "cards/inbox|원본 UID를 화면에 노출하지 않고" \
  "policies|정책 초안 생성" \
  "attendance-days|출석 날짜 생성"; do
  route="${route_and_text%%|*}"
  expected="${route_and_text#*|}"
  curl --fail --silent --show-error --cookie "${department_cookie}" \
    --output "${page}" \
    "${base_url}/admin/departments/${department_id}/${route}"
  assert_contains "${page}" "${expected}"
  if [[ "${route}" == "cards/inbox" ]]; then
    assert_not_contains "${page}" "04ABCDEF"
    if grep --fixed-strings --quiet "****CDEF" "${page}"; then
      assert_contains "${page}" "이 카드 연결"
    fi
  fi
done

curl --fail --silent --show-error --cookie "${department_cookie}" \
  --output "${page}" \
  "${base_url}/admin/departments/${department_id}/teachers"
assert_contains "${page}" "현재 멤버"
assert_contains "${page}" "data-row-href"
member_id="$(sed -n 's#.*href="/admin/departments/[0-9][0-9]*/teachers/\([0-9][0-9]*\)".*#\1#p' \
  "${page}" | head -n 1)"
if [[ -z "${member_id}" ]]; then
  echo "Teacher detail link was not rendered." >&2
  exit 1
fi

curl --fail --silent --show-error --cookie "${department_cookie}" \
  --output "${page}" \
  "${base_url}/admin/departments/${department_id}/teachers/${member_id}"
assert_contains "${page}" "기본정보"
assert_contains "${page}" "출석 통계"
assert_contains "${page}" "최근 출석 이력"
assert_contains "${page}" "생년월일"
assert_contains "${page}" ">수정</a>"
assert_not_contains "${page}" "기본정보 저장"

curl --fail --silent --show-error --cookie "${department_cookie}" \
  --output "${page}" \
  "${base_url}/admin/departments/${department_id}/teachers/${member_id}?edit=true"
assert_contains "${page}" "수정 모드"
assert_contains "${page}" "기본정보 저장"
assert_contains "${page}" "NFC 카드"

curl --fail --silent --show-error --cookie "${department_cookie}" \
  --output "${page}" \
  "${base_url}/admin/departments/${department_id}/history"
assert_contains "${page}" "태깅 이력"

echo "Local admin frontend E2E passed."
