/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

package com.techeazyhq.kit.saga;

/**
 * Told of every change to a saga, for metrics, audit and alerts. It receives the saga after the change; it holds ids only. It
 * must not throw and must not block: a failure in a listener never changes a saga.
 */
@FunctionalInterface
public interface SagaListener {

    void onChange(SagaInstance saga);

    SagaListener NONE = saga -> { };
}
