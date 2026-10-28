#!/usr/bin/env python3
"""채팅 응답의 conditions를 추천 API에 그대로 전달하는 실행 예제. 외부 패키지 불필요."""

import argparse
import json
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


SAMPLE = Path(__file__).with_name("conversation-response.example.json")


def recommendation_input(response):
    if not isinstance(response, dict):
        raise ValueError("채팅 API가 반환한 JSON 객체를 넣어 주세요.")
    if response.get("readyForRecommendation") is not True:
        question = response.get("nextQuestion")
        raise ValueError("아직 조건이 완성되지 않았어요. " + (question if isinstance(question, str) else "필요한 입력을 먼저 채워 주세요."))
    if response.get("issues") != [] or response.get("deletionConfirmation") is not None:
        raise ValueError("입력 문제나 장소 삭제 확인을 먼저 해결해 주세요.")
    conditions = response.get("conditions")
    if not isinstance(conditions, dict) or not {"maxDeposit", "maxRent", "places"} <= conditions.keys():
        raise ValueError("응답에 공통 conditions가 없어요. 채팅 API의 응답 전체를 넣어 주세요.")
    # 위치·중요도·주/월 빈도 등을 바꾸거나 visitsPerWeek를 만들어 넣지 않는다.
    return conditions


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description="채팅 응답 → 추천 API 요청. 기본은 미리보기이며 요청하지 않습니다.")
    parser.add_argument("response", nargs="?", default=str(SAMPLE), help="저장한 채팅 응답 JSON 파일. - 이면 표준 입력. 생략하면 샘플 미리보기")
    parser.add_argument("--base-url", default="http://localhost:8080", help="추천 API가 실행 중인 서버 주소")
    parser.add_argument("--send", action="store_true", help="실제 POST 요청 1회. 동료의 공통 JSON 수정 반영 후 사용하세요. 서버에서 경로 API 사용량이 발생할 수 있습니다.")
    args = parser.parse_args()
    try:
        if args.response == "-":
            response = json.load(sys.stdin)
        else:
            response = json.loads(Path(args.response).read_text(encoding="utf-8"))
        conditions = recommendation_input(response)
        url = args.base_url.rstrip("/") + "/api/recommend"
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username or parsed.password:
            raise ValueError("서버 주소는 http://localhost:8080 같은 형식으로 넣어 주세요.")
        payload = json.dumps(conditions, ensure_ascii=False, allow_nan=False).encode("utf-8")
    except (OSError, ValueError) as error:
        print("전송하지 않았습니다: " + str(error), file=sys.stderr)
        return 1

    if args.response != "-" and Path(args.response).resolve() == SAMPLE.resolve():
        print("샘플 응답을 사용합니다. 실제 채팅에서 생성된 결과가 아닙니다.", file=sys.stderr)
    if not args.send:
        print("미리보기: 실제 요청은 보내지 않았습니다.")
        print("POST " + url)
        print("Content-Type: application/json\n")
        print(json.dumps(conditions, ensure_ascii=False, indent=2, allow_nan=False))
        return 0

    request = Request(url, data=payload, headers={"Content-Type": "application/json"}, method="POST")
    try:
        # 채팅 API를 다시 호출하지 않는다. 재시도·다른 주소로의 자동 전송도 하지 않는다.
        with build_opener(NoRedirect).open(request, timeout=90) as result:
            print("HTTP " + str(result.status))
            print(result.read().decode("utf-8"))
    except HTTPError as error:
        print("HTTP " + str(error.code), file=sys.stderr)
        print(error.read().decode("utf-8", errors="replace"), file=sys.stderr)
        return 1
    except (URLError, TimeoutError):
        print("서버 응답을 받지 못했어요. 서버 주소와 실행 상태를 확인해 주세요. 자동 재시도는 하지 않았습니다.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
