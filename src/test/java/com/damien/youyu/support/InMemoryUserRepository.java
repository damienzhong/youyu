package com.damien.youyu.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;

import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;

/**
 * 内存态 {@link UserRepository} 实现，供 {@code AuthService} 的属性测试使用。
 *
 * <p>这是一个<strong>真实的存储实现</strong>（用 Map 保存用户、自增主键、按账号标识检索），
 * 而非 mock：它不预置任何桩返回值来「制造通过」，被测的鉴权业务逻辑（长度校验、BCrypt 加盐哈希、
 * 失败计数与锁定计时）全部真实执行。目的是让 jqwik 属性测试在数百次随机迭代下保持确定、快速，
 * 不依赖 Spring 上下文与数据库。仅实现 {@code AuthService} 实际使用到的方法，其余按契约抛出
 * {@link UnsupportedOperationException}。</p>
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<Long, User> byId = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public Optional<User> findByUsername(String username) {
        return byId.values().stream()
                .filter(u -> u.getUsername() != null && u.getUsername().equals(username))
                .findFirst();
    }

    @Override
    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    @Override
    public Optional<User> findByWxOpenid(String wxOpenid) {
        return byId.values().stream()
                .filter(u -> u.getWxOpenid() != null && u.getWxOpenid().equals(wxOpenid))
                .findFirst();
    }

    @Override
    public <S extends User> S save(S entity) {
        if (entity.getId() == null) {
            entity.setId(sequence.incrementAndGet());
        }
        byId.put(entity.getId(), entity);
        return entity;
    }

    @Override
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public boolean existsById(Long id) {
        return byId.containsKey(id);
    }

    @Override
    public List<User> findAll() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public long count() {
        return byId.size();
    }

    @Override
    public void deleteById(Long id) {
        byId.remove(id);
    }

    @Override
    public void delete(User entity) {
        if (entity.getId() != null) {
            byId.remove(entity.getId());
        }
    }

    @Override
    public void deleteAll() {
        byId.clear();
    }

    // ---- 以下方法未被鉴权业务逻辑使用，按接口契约保留但不实现 ----

    @Override
    public <S extends User> List<S> saveAll(Iterable<S> entities) {
        List<S> out = new ArrayList<>();
        for (S e : entities) {
            out.add(save(e));
        }
        return out;
    }

    @Override
    public List<User> findAllById(Iterable<Long> ids) {
        List<User> out = new ArrayList<>();
        for (Long id : ids) {
            findById(id).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public void deleteAllById(Iterable<? extends Long> ids) {
        ids.forEach(byId::remove);
    }

    @Override
    public void deleteAll(Iterable<? extends User> entities) {
        entities.forEach(this::delete);
    }

    @Override
    public List<User> findAll(Sort sort) {
        return findAll();
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        return new PageImpl<>(findAll());
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public <S extends User> S saveAndFlush(S entity) {
        return save(entity);
    }

    @Override
    public <S extends User> List<S> saveAllAndFlush(Iterable<S> entities) {
        return saveAll(entities);
    }

    @Override
    public void deleteAllInBatch(Iterable<User> entities) {
        deleteAll(entities);
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<Long> ids) {
        deleteAllById(ids);
    }

    @Override
    public void deleteAllInBatch() {
        deleteAll();
    }

    @Override
    @SuppressWarnings("deprecation")
    public User getOne(Long id) {
        return byId.get(id);
    }

    @Override
    @SuppressWarnings("deprecation")
    public User getById(Long id) {
        return byId.get(id);
    }

    @Override
    public User getReferenceById(Long id) {
        return byId.get(id);
    }

    @Override
    public <S extends User> Optional<S> findOne(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends User> List<S> findAll(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends User> List<S> findAll(Example<S> example, Sort sort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends User> Page<S> findAll(Example<S> example, Pageable pageable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends User> long count(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends User> boolean exists(Example<S> example) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends User, R> R findBy(
            Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {
        throw new UnsupportedOperationException();
    }
}
