/*
 * -\-\-
 * Mobius
 * --
 * Copyright (c) 2017-2018 Spotify AB
 * --
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
 * -/-/-
 */
package com.spotify.mobius.rx2;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.auto.value.AutoValue;
import com.spotify.mobius.rx2.RxMobius.SubtypeEffectHandlerBuilder;
import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.observers.TestObserver;
import io.reactivex.subjects.PublishSubject;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MobiusEffectRouterTest {

  private TestObserver<TestEvent> testSubscriber;
  private PublishSubject<TestEffect> publishSubject;
  private TestConsumer<C> cConsumer;
  private TestAction dAction;

  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    cConsumer = new TestConsumer<>();
    dAction = new TestAction();
    ObservableTransformer<TestEffect, TestEvent> router =
        RxMobius.<TestEffect, TestEvent>subtypeEffectHandler()
            .add(A.class, (Observable<A> as) -> as.map(a -> AEvent.create(a.id())))
            .add(B.class, (Observable<B> bs) -> bs.map(b -> BEvent.create(b.id())))
            .add(C.class, cConsumer)
            .add(D.class, dAction)
            .build();

    publishSubject = PublishSubject.create();
    testSubscriber = TestObserver.create();

    publishSubject.compose(router).subscribe(testSubscriber);
  }

  @Test
  public void shouldRouteEffectToPerformer() throws Exception {
    publishSubject.onNext(A.create(456));
    publishSubject.onComplete();

    testSubscriber.awaitTerminalEvent();
    testSubscriber.assertValue(AEvent.create(456));
  }

  @Test
  public void shouldRouteEffectToConsumer() throws Exception {
    publishSubject.onNext(C.create(456));
    publishSubject.onComplete();

    testSubscriber.awaitTerminalEvent();
    assertThat(cConsumer.getCurrentValue(), is(equalTo(C.create(456))));
  }

  @Test
  public void shouldRunActionOnConsumer() throws Exception {
    publishSubject.onNext(D.create(123));
    publishSubject.onNext(D.create(456));
    publishSubject.onNext(D.create(789));
    publishSubject.onComplete();

    testSubscriber.awaitTerminalEvent();
    assertThat(dAction.getRunCount(), is(equalTo(3)));
  }

  @Test
  public void shouldFailForUnhandledEffect() throws Exception {
    Unhandled unhandled = Unhandled.create();
    publishSubject.onNext(unhandled);

    testSubscriber.awaitTerminalEvent();
    testSubscriber.assertError(new UnknownEffectException(unhandled));
  }

  @Test
  public void shouldReportEffectClassCollisionWhenAddingSuperclass() throws Exception {
    SubtypeEffectHandlerBuilder<TestEffect, TestEvent> builder =
        RxMobius.<TestEffect, TestEvent>subtypeEffectHandler()
            .add(Child.class, childObservable -> null);

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("collision");

    builder.add(Parent.class, parentObservable -> null);
  }

  @Test
  public void shouldReportEffectClassCollisionWhenAddingSubclass() throws Exception {
    SubtypeEffectHandlerBuilder<TestEffect, TestEvent> builder =
        RxMobius.<TestEffect, TestEvent>subtypeEffectHandler()
            .add(Parent.class, observable -> null);

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("collision");

    builder.add(Child.class, observable -> null);
  }

  @Test
  public void effectHandlersShouldBeImmutable() throws Exception {
    // redo some test setup for test case specific conditions
    publishSubject = PublishSubject.create();
    testSubscriber = TestObserver.create();

    SubtypeEffectHandlerBuilder<TestEffect, TestEvent> builder =
        RxMobius.<TestEffect, TestEvent>subtypeEffectHandler()
            .add(A.class, (Observable<A> as) -> as.map(a -> AEvent.create(a.id())));

    ObservableTransformer<TestEffect, TestEvent> router = builder.build();

    // this should not lead to the effects router being capable of handling B effects
    builder.add(B.class, (Observable<B> bs) -> bs.map(b -> BEvent.create(b.id())));

    publishSubject.compose(router).subscribe(testSubscriber);

    B effect = B.create(84);
    publishSubject.onNext(effect);
    publishSubject.onComplete();

    testSubscriber.awaitTerminalEvent();
    testSubscriber.assertError(new UnknownEffectException(effect));
  }

  @Test
  public void shouldSupportCustomErrorHandler() throws Exception {
    // redo some test setup for test case specific conditions
    publishSubject = PublishSubject.create();
    testSubscriber = TestObserver.create();

    final RuntimeException expectedException = new RuntimeException("expected!");
    final AtomicBoolean gotRightException = new AtomicBoolean(false);

    SubtypeEffectHandlerBuilder<TestEffect, TestEvent> builder =
        RxMobius.<TestEffect, TestEvent>subtypeEffectHandler()
            .add(
                A.class,
                (Observable<A> as) ->
                    as.map(
                        a -> {
                          throw expectedException;
                        }))
            .withFatalErrorHandler(
                new Function<
                    ObservableTransformer<? extends TestEffect, TestEvent>, Consumer<Throwable>>() {
                  @Override
                  public Consumer<Throwable> apply(
                      ObservableTransformer<? extends TestEffect, TestEvent> testEventTransformer) {
                    return new Consumer<Throwable>() {
                      @Override
                      public void accept(Throwable throwable) {
                        if (throwable.equals(expectedException)) {
                          gotRightException.set(true);
                        } else {
                          throwable.printStackTrace();
                          Assert.fail("got the wrong exception!");
                        }
                      }
                    };
                  }
                });

    ObservableTransformer<TestEffect, TestEvent> router = builder.build();

    publishSubject.compose(router).subscribe(testSubscriber);

    publishSubject.onNext(A.create(1));

    assertThat(gotRightException.get(), is(true));

    testSubscriber.awaitTerminalEvent();
    testSubscriber.assertError(expectedException);
  }

  private interface TestEffect {}

  @AutoValue
  abstract static class A implements TestEffect {

    abstract int id();

    static A create(int id) {
      return new AutoValue_MobiusEffectRouterTest_A(id);
    }
  }

  @AutoValue
  public abstract static class B implements TestEffect {

    abstract int id();

    static B create(int id) {
      return new AutoValue_MobiusEffectRouterTest_B(id);
    }
  }

  @AutoValue
  public abstract static class C implements TestEffect {

    abstract int id();

    static C create(int id) {
      return new AutoValue_MobiusEffectRouterTest_C(id);
    }
  }

  @AutoValue
  public abstract static class D implements TestEffect {

    abstract int id();

    static D create(int id) {
      return new AutoValue_MobiusEffectRouterTest_D(id);
    }
  }

  @AutoValue
  public abstract static class Unhandled implements TestEffect {

    static Unhandled create() {
      return new AutoValue_MobiusEffectRouterTest_Unhandled();
    }
  }

  private static class Parent implements TestEffect {}

  private static class Child extends Parent {}

  private interface TestEvent {}

  @AutoValue
  public abstract static class AEvent implements TestEvent {

    abstract int id();

    static AEvent create(int id) {
      return new AutoValue_MobiusEffectRouterTest_AEvent(id);
    }
  }

  @AutoValue
  public abstract static class BEvent implements TestEvent {

    abstract int id();

    static BEvent create(int id) {
      return new AutoValue_MobiusEffectRouterTest_BEvent(id);
    }
  }
}
