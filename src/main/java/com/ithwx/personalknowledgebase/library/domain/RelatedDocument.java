package com.ithwx.personalknowledgebase.library.domain;

import java.util.List;

/** 共同概念表示主题相关，不表示因果关系或答案依据。 */
public record RelatedDocument(Long id, Long version, String name, List<String> sharedConcepts) {
}
