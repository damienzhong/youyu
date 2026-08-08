package com.damien.youyu.api.dto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;

/**
 * 分类列表响应体：按 kind（支出/收入）分组、含两级层级（需求 5.6）。
 *
 * <p>{@code expense} 与 {@code income} 各为该用户对应种类的顶级（父）分类列表，每个父分类
 * 内嵌其子分类。支出与收入分类各自独立（需求 5.6）。</p>
 */
public record CategoryListResponse(List<Node> expense, List<Node> income) {

    /** 层级节点：父分类含 children；子分类的 children 恒为空列表。 */
    public record Node(Long id, String name, Long parentId, String icon, String iconColor, List<Node> children) {
    }

    /**
     * 由该用户的全部分类构建按 kind 分组的两级树。
     *
     * <p>顶级分类（parentId 为 null）按 id 升序；其子分类按 id 升序内嵌。</p>
     */
    public static CategoryListResponse from(List<Category> categories) {
        return new CategoryListResponse(
                buildTree(categories, CategoryKind.EXPENSE),
                buildTree(categories, CategoryKind.INCOME));
    }

    private static List<Node> buildTree(List<Category> categories, CategoryKind kind) {
        List<Category> ofKind = categories.stream()
                .filter(c -> c.getKind() == kind)
                .toList();

        Map<Long, List<Category>> childrenByParent = ofKind.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));

        List<Node> roots = new ArrayList<>();
        ofKind.stream()
                .filter(c -> c.getParentId() == null)
                .sorted(Comparator.comparing(Category::getId))
                .forEach(parent -> {
                    List<Node> children = childrenByParent
                            .getOrDefault(parent.getId(), List.of())
                            .stream()
                            .sorted(Comparator.comparing(Category::getId))
                            .map(c -> new Node(c.getId(), c.getName(), c.getParentId(), c.getIcon(),
                                    c.getIconColor(), List.of()))
                            .toList();
                    roots.add(new Node(parent.getId(), parent.getName(), null, parent.getIcon(),
                            parent.getIconColor(), children));
                });
        return roots;
    }
}
