package plainround.catalogimport.domain;

/** Why a row was refused: the value side of {@link ProductRules#validate}'s result. */
public record Rejection(String reason) {}
