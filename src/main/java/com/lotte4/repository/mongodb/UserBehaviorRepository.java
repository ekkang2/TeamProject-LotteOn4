package com.lotte4.repository.mongodb;

import com.lotte4.dto.mongodb.RecommendationResult;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserBehaviorRepository {

    private final MongoTemplate mongoTemplate;

    public UserBehaviorRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public List<RecommendationResult> findRelatedProducts(int targetProdId, String currentUserId) {
        // 1. 대상 상품을 조회한 사용자 목록을 가져옴 - 현재 사용자(currentUserId)는 제외
        MatchOperation matchUsersWhoViewedTarget = Aggregation.match(
                Criteria.where("prodId").is(targetProdId)
                        .and("uid").ne(currentUserId) // 현재 사용자 제외
        );

        // 2. 해당 상품을 본 사용자들의 ID(uid) 목록을 그룹화하여 가져옴
        GroupOperation groupByUser = Aggregation.group("uid").first("uid").as("uid"); // uid(사용자 ID) 기준으로 그룹화

        // 3. 그룹화된 사용자 ID를 기준으로 userLogs 컬렉션에서 해당 사용자가 조회한 다른 상품 목록 가져오기
        LookupOperation lookupProductsViewedByUsers = Aggregation.lookup("userLogs", "uid", "uid", "userViews");

        // 4.조회한 userViews 배열을 개별 문서로 변환하여 각 상품 조회를 개별적으로 처리
        UnwindOperation unwindViews = Aggregation.unwind("userViews");

        // 5. 대상 상품(targetProdId)과 동일한 상품은 제외하여 필터링
        MatchOperation matchOtherProducts = Aggregation.match(
                Criteria.where("userViews.prodId").ne(targetProdId)
        );

        // 6. 상품 ID(prodId) 기준으로 그룹화하고, 각 상품이 조회된 횟수를 카운트
        GroupOperation groupByProdId = Aggregation.group("userViews.prodId")
                .count().as("viewCount"); // 조회 횟수 계산

        // 7. 결과를 relatedProdId(연관 상품 ID)와 viewCount(조회 횟수) 형식으로 변환
        ProjectionOperation projectToRelatedProduct = Aggregation.project("viewCount")
                .and("_id").as("relatedProdId"); // 조회된 상품 ID를 relatedProdId로 저장

        // 8. 조회 횟수를 기준으로 내림차순 정렬 후 상위 5개 상품만 선택
        SortOperation sortByViewCount = Aggregation.sort(Sort.by(Sort.Direction.DESC, "viewCount"));

        Aggregation aggregation = Aggregation.newAggregation(
                matchUsersWhoViewedTarget,     // 대상 상품을 조회한 사용자 찾기
                groupByUser,                   // 사용자별로 그룹화
                lookupProductsViewedByUsers,   // 해당 사용자가 조회한 다른 상품 가져오기
                unwindViews,                   // 배열을 개별 문서로 변환
                matchOtherProducts,            // 대상 상품과 동일한 상품 필터링
                groupByProdId,                 // 조회된 상품을 그룹화하고 조회 횟수 계산
                projectToRelatedProduct,       // 조회 결과를 연관 상품 ID와 조회 횟수 형식으로 변환
                sortByViewCount,               // 조회 횟수 기준으로 정렬
                Aggregation.limit(5)           // 상위 5개 상품만 선택
        );

        // 9. MongoDB Aggregation 실행 및 결과 반환
        AggregationResults<RecommendationResult> results = mongoTemplate.aggregate(aggregation, "userLogs", RecommendationResult.class);
        return results.getMappedResults();
    }
}
