/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.expression.spel.ast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.Test;

import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.junit.Assert.*;

/**
 * Unit tests for SpEL's matches operator, in particular for the bounded
 * pattern cache that guards against unlimited growth when the regular
 * expression is supplied at evaluation time.
 *
 * @see OperatorMatches
 */
public class OperatorMatchesTests {

	private static final int MAX = OperatorMatches.MAX_PATTERN_CACHE_SIZE;


	@Test
	public void maxPatternCacheSize() {
		assertEquals(256, OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	public void parserPatternCacheIsBoundedForDynamicPatterns() throws Exception {
		SpelExpression expression = parse("'abc' matches #regex");
		OperatorMatches matches = (OperatorMatches) expression.getAST();
		StandardEvaluationContext context = new StandardEvaluationContext();

		for (int i = 0; i < MAX * 4; i++) {
			context.setVariable("regex", "abc|x" + i);
			assertTrue(expression.getValue(context, Boolean.class));
			assertTrue(patternCache(matches).size() <= MAX);
		}
		assertEquals(MAX, patternCache(matches).size());
	}

	@Test
	public void parserPatternCacheIsSharedAndBoundedAcrossMatchesNodes() throws Exception {
		SpelExpression expression = parse("('abc' matches #regex1) and ('abc' matches #regex2)");
		OpAnd and = (OpAnd) expression.getAST();
		OperatorMatches first = (OperatorMatches) and.getChild(0);
		OperatorMatches second = (OperatorMatches) and.getChild(1);
		assertSame(patternCache(first), patternCache(second));

		StandardEvaluationContext context = new StandardEvaluationContext();
		for (int i = 0; i < MAX * 2; i++) {
			context.setVariable("regex1", "abc|first" + i);
			context.setVariable("regex2", "abc|second" + i);
			assertTrue(expression.getValue(context, Boolean.class));
		}
		assertEquals(MAX, patternCache(first).size());
	}

	@Test
	public void parserPatternCacheEvictsLeastRecentlyUsedPattern() throws Exception {
		SpelExpression expression = parse("'abc' matches #regex");
		OperatorMatches matches = (OperatorMatches) expression.getAST();
		StandardEvaluationContext context = new StandardEvaluationContext();

		for (int i = 0; i < MAX; i++) {
			context.setVariable("regex", "abc|x" + i);
			assertTrue(expression.getValue(context, Boolean.class));
		}
		Set<Object> keys = cachedKeys(matches);
		assertEquals(MAX, keys.size());
		assertTrue(keys.contains("abc|x0"));
		assertTrue(keys.contains("abc|x1"));

		// Use the oldest pattern again so that it becomes the most recently used one
		context.setVariable("regex", "abc|x0");
		assertTrue(expression.getValue(context, Boolean.class));

		// Add a new pattern: the least recently used one ("abc|x1") gets evicted
		context.setVariable("regex", "abc|new");
		assertTrue(expression.getValue(context, Boolean.class));

		keys = cachedKeys(matches);
		assertEquals(MAX, keys.size());
		assertTrue(keys.contains("abc|x0"));
		assertFalse(keys.contains("abc|x1"));
		assertTrue(keys.contains("abc|x2"));
		assertTrue(keys.contains("abc|new"));
	}

	@Test
	public void parserPatternCacheStillMatchesCorrectlyAfterEviction() throws Exception {
		SpelExpression expression = parse("#input matches #regex");
		StandardEvaluationContext context = new StandardEvaluationContext();

		for (int i = 0; i < MAX * 2; i++) {
			context.setVariable("input", "x" + i);
			context.setVariable("regex", "x" + i);
			assertTrue(expression.getValue(context, Boolean.class));
			context.setVariable("input", "y" + i);
			assertFalse(expression.getValue(context, Boolean.class));
		}

		// A previously evicted pattern is compiled again and still evaluated correctly
		context.setVariable("input", "x0");
		context.setVariable("regex", "x0");
		assertTrue(expression.getValue(context, Boolean.class));
		context.setVariable("input", "x1");
		assertFalse(expression.getValue(context, Boolean.class));
		assertEquals(MAX, patternCache((OperatorMatches) expression.getAST()).size());
	}

	@Test
	public void parserPatternCacheIsBoundedUnderConcurrentEvaluation() throws Exception {
		final SpelExpression expression = parse("'abc' matches #regex");
		final int threadCount = 8;
		final CountDownLatch start = new CountDownLatch(1);
		final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		List<Thread> threads = new ArrayList<Thread>();
		for (int t = 0; t < threadCount; t++) {
			final int threadIndex = t;
			Thread thread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						start.await();
						StandardEvaluationContext context = new StandardEvaluationContext();
						for (int i = 0; i < MAX * 2; i++) {
							context.setVariable("regex", "abc|t" + threadIndex + "_" + i);
							if (!expression.getValue(context, Boolean.class)) {
								throw new IllegalStateException("Pattern did not match");
							}
						}
					}
					catch (Throwable ex) {
						failure.compareAndSet(null, ex);
					}
				}
			});
			threads.add(thread);
			thread.start();
		}
		start.countDown();
		for (Thread thread : threads) {
			thread.join();
		}
		assertNull(failure.get());
		assertEquals(MAX, patternCache((OperatorMatches) expression.getAST()).size());
	}

	@Test
	public void defaultConstructorPatternCacheIsBounded() throws Exception {
		OperatorMatches matches = new OperatorMatches(-1,
				new StringLiteral("'abc'", -1, "'abc'"), new VariableReference("regex", -1));
		StandardEvaluationContext context = new StandardEvaluationContext();

		for (int i = 0; i < MAX * 4; i++) {
			context.setVariable("regex", "abc|x" + i);
			assertTrue(matches.getValue(new ExpressionState(context), Boolean.class));
			assertTrue(patternCache(matches).size() <= MAX);
		}
		assertEquals(MAX, patternCache(matches).size());

		// Patterns that are no longer cached are still evaluated correctly
		context.setVariable("regex", "abc|uncached");
		assertTrue(matches.getValue(new ExpressionState(context), Boolean.class));
		context.setVariable("regex", "x|y");
		assertFalse(matches.getValue(new ExpressionState(context), Boolean.class));
		assertEquals(MAX, patternCache(matches).size());

		// Cached patterns are still used
		context.setVariable("regex", "abc|x0");
		assertTrue(matches.getValue(new ExpressionState(context), Boolean.class));
		assertTrue(cachedKeys(matches).contains("abc|x0"));
	}

	@Test
	public void regexLengthIsCheckedForUnseenAndRepeatedEvaluation() throws Exception {
		SpelExpression expression = parse("'c' matches #regex");
		OperatorMatches matches = (OperatorMatches) expression.getAST();
		StandardEvaluationContext context = new StandardEvaluationContext();

		String pattern = "^(" + repeat("12345", 199) + "|c)";
		assertEquals(1000, pattern.length());
		context.setVariable("regex", pattern);
		assertTrue(expression.getValue(context, Boolean.class));
		assertTrue(expression.getValue(context, Boolean.class));
		assertTrue(cachedKeys(matches).contains(pattern));

		String tooLong = pattern + "?";
		assertEquals(1001, tooLong.length());
		context.setVariable("regex", tooLong);
		for (int i = 0; i < 3; i++) {
			try {
				expression.getValue(context, Boolean.class);
				fail("Should have failed with " + SpelMessage.MAX_REGEX_LENGTH_EXCEEDED);
			}
			catch (SpelEvaluationException ex) {
				assertEquals(SpelMessage.MAX_REGEX_LENGTH_EXCEEDED, ex.getMessageCode());
			}
		}
		assertFalse(cachedKeys(matches).contains(tooLong));
		assertEquals(1, patternCache(matches).size());
	}

	@Test
	public void regexLengthIsCheckedEvenIfPatternIsAlreadyCached() throws Exception {
		String tooLong = "^(" + repeat("12345", 199) + "|c)?";
		assertEquals(1001, tooLong.length());
		OperatorMatches matches = new OperatorMatches(-1,
				new StringLiteral("'abc'", -1, "'abc'"), new VariableReference("regex", -1));
		// Simulate an over-long pattern that already made it into the cache
		@SuppressWarnings("unchecked")
		Map<String, Pattern> cache = (Map<String, Pattern>) patternCache(matches);
		cache.put(tooLong, Pattern.compile(tooLong));

		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("regex", tooLong);
		try {
			matches.getValue(new ExpressionState(context), Boolean.class);
			fail("Should have failed with " + SpelMessage.MAX_REGEX_LENGTH_EXCEEDED);
		}
		catch (SpelEvaluationException ex) {
			assertEquals(SpelMessage.MAX_REGEX_LENGTH_EXCEEDED, ex.getMessageCode());
		}
	}


	private static SpelExpression parse(String expression) {
		return (SpelExpression) new SpelExpressionParser().parseExpression(expression);
	}

	private static Map<?, ?> patternCache(OperatorMatches matches) throws Exception {
		Field field = OperatorMatches.class.getDeclaredField("patternCache");
		field.setAccessible(true);
		return (Map<?, ?>) field.get(matches);
	}

	/**
	 * Copy of the cached regexes, taken by iteration so that the check itself
	 * does not count as an access of the cached patterns.
	 */
	private static Set<Object> cachedKeys(OperatorMatches matches) throws Exception {
		return new HashSet<Object>(patternCache(matches).keySet());
	}

	private static String repeat(String str, int count) {
		StringBuilder result = new StringBuilder(str.length() * count);
		for (int i = 0; i < count; i++) {
			result.append(str);
		}
		return result.toString();
	}

}
