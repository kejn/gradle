/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.snapshot.children;

import com.google.common.collect.ImmutableList;
import org.gradle.internal.snapshot.CaseSensitivity;
import org.gradle.internal.snapshot.VfsRelativePath;

import java.util.List;
import java.util.Optional;

public class SingletonChildMap<T> implements ChildMap<T> {
    private final Entry<T> entry;
    private final CaseSensitivity caseSensitivity;

    public SingletonChildMap(String path, T child, CaseSensitivity caseSensitivity) {
        this(new Entry<>(path, child), caseSensitivity);
    }

    public SingletonChildMap(Entry<T> entry, CaseSensitivity caseSensitivity) {
        this.entry = entry;
        this.caseSensitivity = caseSensitivity;
    }

    @Override
    public Optional<T> get(VfsRelativePath relativePath) {
        return Optional.empty();
    }

    @Override
    public <R> R handlePath(VfsRelativePath relativePath, PathRelationshipHandler<R> handler) {
        return entry.handlePath(relativePath, 0, caseSensitivity, handler);
    }

    @Override
    public T get(int index) {
        checkIndex(index);
        return entry.getValue();
    }

    @Override
    public ChildMap<T> withNewChild(int insertBefore, String path, T newChild) {
        Entry<T> newEntry = new Entry<>(path, newChild);
        List<Entry<T>> newChildren = insertBefore == 0
            ? ImmutableList.of(newEntry, entry)
            : ImmutableList.of(entry, newEntry);
        return new DefaultChildMap<>(newChildren, caseSensitivity);
    }

    @Override
    public ChildMap<T> withReplacedChild(int childIndex, T newChild) {
        checkIndex(childIndex);
        if (entry.getValue().equals(newChild)) {
            return this;
        }
        return new SingletonChildMap<>(entry.getPath(), newChild, caseSensitivity);
    }

    @Override
    public ChildMap<T> withRemovedChild(int childIndex) {
        checkIndex(childIndex);
        return new EmptyChildMap<>(caseSensitivity);
    }

    private void checkIndex(int childIndex) {
        if (childIndex != 0) {
            throw new IndexOutOfBoundsException("Index out of range: " + childIndex);
        }
    }
}
