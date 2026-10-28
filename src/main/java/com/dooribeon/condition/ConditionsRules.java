package com.dooribeon.condition;

import com.dooribeon.condition.ConditionCheckResult.Issue;
import com.dooribeon.condition.ConditionCheckResult.Kind;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.TargetType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 공통 조건에 기본값을 적용하고 입력 준비 여부를 검사한다. 자연어 해석·추천 계산은 하지 않는다. */
@Component
public class ConditionsRules {
    // JSON 숫자를 사용하는 화면에서도 정밀도를 유지하는 기술적 상한이다.
    private static final long MAX_SAFE_MONEY = 9007199254740991L;

    /** 이미 모인 조건을 검사한다. 기본값·단위 확인 등 대화 정보가 있으면 아래 메서드를 사용한다. */
    public ConditionCheckResult check(Conditions conditions) {
        return check(conditions, InputContext.empty());
    }

    public ConditionCheckResult check(Conditions conditions, InputContext context) {
        if (context == null) throw new IllegalArgumentException("입력 맥락은 null일 수 없습니다.");
        List<Issue> issues = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if (conditions == null) {
            add(issues, "conditions", Kind.INVALID, "조건을 다시 입력해 주세요.");
            return new ConditionCheckResult(null, issues, notices);
        }

        checkMoney(conditions.maxDeposit(), "maxDeposit", "보증금", issues);
        checkMoney(conditions.maxRent(), "maxRent", "관리비를 제외한 월세", issues);
        if (context.needsAmountUnitConfirmation()) {
            add(issues, "budget", Kind.NEEDS_CONFIRMATION,
                    "금액 단위를 확인할게요. '100만 원'처럼 원 단위를 붙여 알려주세요.");
        }
        if (conditions.places() == null) {
            add(issues, "places", Kind.INVALID, "장소가 없으면 빈 목록으로 보내 주세요.");
            return new ConditionCheckResult(conditions, issues, notices);
        }

        Set<String> seenIds = new HashSet<>();
        List<PlaceCondition> checkedPlaces = new ArrayList<>();
        for (int i = 0; i < conditions.places().size(); i++) {
            String path = "places[" + i + "]";
            PlaceCondition place = conditions.places().get(i);
            if (place == null) {
                add(issues, path, Kind.INVALID, "잘못된 장소 항목을 확인해 주세요.");
                checkedPlaces.add(null);
                continue;
            }
            if (blank(place.id())) {
                add(issues, path + ".id", Kind.INVALID, "장소를 구분할 번호가 필요해요.");
            } else if (!seenIds.add(place.id())) {
                add(issues, path + ".id", Kind.INVALID, "장소 번호가 중복됐어요. 다시 확인해 주세요.");
            }
            place = applyImportanceDefault(place, context, notices);
            checkedPlaces.add(place);
            checkPlace(place, path, issues, notices);
        }
        return new ConditionCheckResult(new Conditions(conditions.maxDeposit(), conditions.maxRent(),
                checkedPlaces), issues, notices);
    }

    private PlaceCondition applyImportanceDefault(PlaceCondition place, InputContext context,
                                                  List<String> notices) {
        // 회사·학교 판단은 후속 추출 단계에서 전달한다. '일주일' 등의 문자열로 추측하지 않는다.
        if (place.targetType() != TargetType.FIXED_PLACE || blank(place.id())
                || place.importance() != null
                || !context.defaultRequiredPlaceIds().contains(place.id())
                || context.userSpecifiedImportanceIds().contains(place.id())) {
            return place;
        }
        notices.add(displayName(place) + "은(는) 서비스 기본값에 따라 중요도를 '필수'로 설정했어요. "
                + "원하시면 중요도를 바꿀 수 있어요.");
        return place.withImportance(Importance.REQUIRED);
    }

    private void checkPlace(PlaceCondition place, String path, List<Issue> issues, List<String> notices) {
        checkOptionalText(place.label(), path + ".label", issues);
        checkOptionalText(place.query(), path + ".query", issues);
        String name = displayName(place);
        if (place.targetType() == null) {
            add(issues, path + ".targetType", Kind.MISSING,
                    name + "은(는) 계속 다닐 특정 장소인가요, 주변에 있으면 되는 시설인가요?");
        } else if (place.targetType() == TargetType.NEARBY_FACILITY) {
            // 아직 정하지 않은 주변 시설 추천을 입력 완료만으로 지원한다고 표시하지 않는다.
            add(issues, path + ".targetType", Kind.UNSUPPORTED,
                    "주변 시설 조건은 아직 추천에 연결되지 않았어요. 특정 장소로 지정하거나 조건을 수정해 주세요.");
        } else if (place.location() == null) {
            add(issues, path + ".location", Kind.MISSING,
                    name + "의 위치를 검색한 뒤 지도나 목록에서 선택해 주세요.");
        }
        if (place.location() != null) checkLocation(place.location(), path + ".location", issues);

        if (place.importance() == Importance.REQUIRED && place.targetType() == TargetType.FIXED_PLACE) {
            if (place.transport() == null) {
                add(issues, path + ".transport", Kind.MISSING, name + "까지 어떤 이동수단을 이용하실 건가요?");
            }
            if (place.maxMinutes() == null) {
                add(issues, path + ".maxMinutes", Kind.MISSING,
                        name + "까지 편도로 최대 몇 분까지 괜찮으세요?");
            }
        } else if (place.targetType() == TargetType.FIXED_PLACE && place.transport() == null) {
            // 공통 조건의 null을 사용자 입력값인 것처럼 바꾸지는 않는다.
            notices.add(name + "의 이동수단은 계산할 때 대중교통을 기본으로 적용해요.");
        }
        if (place.maxMinutes() != null && place.maxMinutes() <= 0) {
            add(issues, path + ".maxMinutes", Kind.INVALID, "최대 편도시간은 1분 이상의 정수로 입력해 주세요.");
        }
        if (place.frequency() != null) {
            if (place.frequency().period() == null) {
                add(issues, path + ".frequency.period", Kind.INVALID, "방문 주기를 주 또는 월로 지정해 주세요.");
            }
            if (place.frequency().count() == null || place.frequency().count() <= 0) {
                add(issues, path + ".frequency.count", Kind.INVALID, "방문 횟수는 1 이상의 정수로 입력해 주세요.");
            }
        }
    }

    private void checkMoney(Long amount, String path, String name, List<Issue> issues) {
        if (amount == null) {
            add(issues, path, Kind.MISSING, name + "은 최대 몇 만원까지 생각하고 있나요?");
        } else if (amount < 0 || amount > MAX_SAFE_MONEY) {
            add(issues, path, Kind.INVALID, name + " 금액을 올바른 정수 만원으로 다시 입력해 주세요.");
        }
    }

    private void checkLocation(PlaceLocation location, String path, List<Issue> issues) {
        checkOptionalText(location.placeId(), path + ".placeId", issues);
        checkOptionalText(location.placeName(), path + ".placeName", issues);
        checkOptionalText(location.address(), path + ".address", issues);
        if (blank(location.placeName()) && blank(location.address())) {
            add(issues, path, Kind.INVALID, "선택한 장소의 이름이나 주소를 확인해 주세요.");
        }
        if (!validCoordinate(location.lat(), -90, 90)) {
            add(issues, path + ".lat", Kind.INVALID, "선택한 장소의 위도를 다시 확인해 주세요.");
        }
        if (!validCoordinate(location.lng(), -180, 180)) {
            add(issues, path + ".lng", Kind.INVALID, "선택한 장소의 경도를 다시 확인해 주세요.");
        }
    }

    private boolean validCoordinate(Double value, double min, double max) {
        return value != null && Double.isFinite(value) && value >= min && value <= max;
    }

    private void checkOptionalText(String value, String path, List<Issue> issues) {
        if (value != null && value.isBlank()) {
            add(issues, path, Kind.INVALID, "내용이 없으면 빈 문자열 대신 null로 보내 주세요.");
        }
    }

    private String displayName(PlaceCondition place) {
        if (!blank(place.label())) return place.label();
        if (place.location() != null) {
            if (!blank(place.location().placeName())) return place.location().placeName();
            if (!blank(place.location().address())) return place.location().address();
        }
        return "이 장소";
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private void add(List<Issue> issues, String path, Kind kind, String message) {
        issues.add(new Issue(path, kind, message));
    }

    /** 공통 JSON 밖에서 서버가 유지할 입력 맥락. 후속 AI 연결·병합 단계에서 전달한다. */
    public record InputContext(
            Set<String> defaultRequiredPlaceIds,
            Set<String> userSpecifiedImportanceIds,
            boolean needsAmountUnitConfirmation
    ) {
        public InputContext {
            defaultRequiredPlaceIds = Set.copyOf(defaultRequiredPlaceIds);
            userSpecifiedImportanceIds = Set.copyOf(userSpecifiedImportanceIds);
        }

        public static InputContext empty() {
            return new InputContext(Set.of(), Set.of(), false);
        }
    }
}
