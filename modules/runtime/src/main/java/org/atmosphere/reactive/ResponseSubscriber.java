/************************************************************************
 * Licensed under Public Domain (CC0)                                    *
 *                                                                       *
 * To the extent possible under law, the person who associated CC0 with  *
 * this code has waived all copyright and related or neighboring         *
 * rights to this code.                                                  *
 *                                                                       *
 * You should have received a copy of the CC0 legalcode along with this  *
 * work. If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.*
 ************************************************************************/
package org.atmosphere.reactive;

import org.atmosphere.util.NonBlockingMutexExecutor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Reactive streams subscriber that represents a response.
 *
 * This subscriber only requests one element at a time, and only if the attached {@link ServletOutputStream} is ready to
 * receive data in a non blocking way. It may be necessary to insert a buffer between this and and a publisher to
 * achieve high through puts.
 *
 * Errors from the {@link org.reactivestreams.Publisher} result in the {@link AsyncContext} being completed, after that
 * {@link #onPublisherError} is invoked - this can be overridden to insert behaviour such as logging or clean up.
 *
 * Errors from the {@link ServletOutputStream} result in the subscription being cancelled, followed by
 * {@link #onOutputStreamError} being invoked - this can be overridden to insert behaviour such as logging or clean up.
 */
public class ResponseSubscriber implements Subscriber<ByteBuffer> {

  private final AsyncContext context;
  private final ServletOutputStream outputStream;

  private final Executor mutex = new NonBlockingMutexExecutor();

  private Subscription subscription;
  private State state = State.IDLE;

  public ResponseSubscriber(final AsyncContext context) throws IOException {
    this.context = context;
    this.outputStream = context.getResponse().getOutputStream();
  }

  /**
   * Invoked when a downstream error occurs, ie, when an error writing to the servlet
   * output stream occurs.
   *
   * Override to insert error handling for downstream writing errors, such as logging.
   *
   * By default this does nothing.
   *
   * This method will be invoked at most once.
   *
   * @param t The error that occurred.
   */
  protected void onOutputStreamError(final Throwable t) {
  }

  /**
   * Invoked when an upstream error occurs, ie, when an error is received from the publisher.
   *
   * Override to insert error handling for downstream writing errors, such as logging.
   *
   * By default this does nothing.
   *
   * @param t The error that occurred.
   */
  protected void onPublisherError(final Throwable t) {
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    Objects.requireNonNull(subscription, "Subscription passed to onSubscribe must not be null");
    mutex.execute(() -> {
      if (this.subscription == null) {
        this.subscription = subscription;
        outputStream.setWriteListener(new SubscriberWriteListener());
        context.addListener(new SubscriberAsyncListener());
        maybeRequest();
      } else {
        subscription.cancel();
      }
    });
  }

  @Override
  public void onNext(final ByteBuffer item) {
    Objects.requireNonNull(item, "Element passed to onNext must not be null");
    mutex.execute(() -> {
      switch (state) {
        case DEMANDING:
          state = State.IDLE;
          try {
            if (item.hasArray()) {
              outputStream.write(item.array(), item.arrayOffset(), item.remaining());
            } else {
              byte[] array = new byte[item.remaining()];
              item.get(array);
              outputStream.write(array);
            }
            // Jetty requires isReady to be invoked before invoking flush
            if (outputStream.isReady()) {
              outputStream.flush();
            }
            maybeRequest();
          } catch (IOException e) {
            streamError(e);
          }
          break;
        case IDLE:
          // Should not happen
          throw new IllegalStateException("onNext with no demand");
        case FINISHED:
          // Ignore
          break;
      }
    });
  }

  private void maybeRequest() {
    if (outputStream.isReady() && state != State.DEMANDING) {
      state = State.DEMANDING;
      subscription.request(1);
    }
  }

  private void streamError(final Throwable t) {
    switch (state) {
      case IDLE:
      case DEMANDING:
        state = State.FINISHED;
        subscription.cancel();
        onOutputStreamError(t);
        break;
      case FINISHED:
        // Already finished, nothing to do.
        break;
    }
  }

  @Override
  public void onError(final Throwable throwable) {
    Objects.requireNonNull(throwable, "Exception passed to onError must not be null");
    mutex.execute(() -> {
      switch (state) {
        case IDLE:
        case DEMANDING:
          state = State.FINISHED;
          onPublisherError(throwable);
          context.complete();
          break;
        case FINISHED:
          // Already finished, nothing to do.
          break;
      }
    });
  }

  @Override
  public void onComplete() {
    mutex.execute(() -> {
      switch (state) {
        case IDLE:
        case DEMANDING:
          state = State.FINISHED;
          context.complete();
          break;
        case FINISHED:
          // Already finished, nothing to do.
          break;
      }
    });
  }

  private class SubscriberWriteListener implements WriteListener {
    @Override
    public void onWritePossible() throws IOException {
      mutex.execute(() -> {
        switch (state) {
          case IDLE:
            state = State.DEMANDING;
            subscription.request(1);
            break;
          default:
            // Nothing to do
            break;
        }
      });
    }

    @Override
    public void onError(Throwable t) {
      mutex.execute(() -> streamError(t));
    }
  }

  private void requestComplete() {
    switch (state) {
      case IDLE:
      case DEMANDING:
        state = State.FINISHED;
        subscription.cancel();
        break;
      case FINISHED:
        // Already finished, nothing to do.
        break;
    }
  }

  private final class SubscriberAsyncListener implements AsyncListener {
    @Override
    public void onComplete(AsyncEvent event) throws IOException {
      mutex.execute(ResponseSubscriber.this::requestComplete);
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException {
      mutex.execute(ResponseSubscriber.this::requestComplete);
    }

    @Override
    public void onError(AsyncEvent event) throws IOException {
      mutex.execute(() -> streamError(event.getThrowable()));
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException {
    }
  }

  private enum State {
    IDLE,
    DEMANDING,
    FINISHED
  }
}