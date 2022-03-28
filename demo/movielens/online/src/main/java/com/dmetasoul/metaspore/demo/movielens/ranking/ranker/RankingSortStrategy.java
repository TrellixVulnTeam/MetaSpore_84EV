package com.dmetasoul.metaspore.demo.movielens.ranking.ranker;

import java.util.Optional;

public class RankingSortStrategy {

    public enum Type
    {
        USE_ONLY_RANK,
        USE_RANK_MULTIPLY_MATCH
    }

    public static final Type DEFAULT_STRATEGY = Type.USE_ONLY_RANK;

    public static Double getScoreByStrategy(Type strategy,
                                            Double retrievalScore,
                                            Double alpha,
                                            Double rankingScore,
                                            Double beta) {

        strategy = Optional.ofNullable(strategy).orElse(DEFAULT_STRATEGY);
        rankingScore = Optional.ofNullable(rankingScore).orElse(0.0);
        double result = 0.0;

        switch (strategy) {
            case USE_ONLY_RANK:
                result =  rankingScore;
                break;
            case USE_RANK_MULTIPLY_MATCH:
                retrievalScore = Optional.ofNullable(retrievalScore).orElse(0.0);
                alpha = Optional.ofNullable(alpha).orElse(0.0);
                beta = Optional.ofNullable(beta).orElse(1.0);
                result = Math.pow(retrievalScore, alpha) * Math.pow(rankingScore, beta);
                break;
        }

        return result;
    }
}
