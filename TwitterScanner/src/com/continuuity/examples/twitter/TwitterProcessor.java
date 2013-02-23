/*
 * TwitterScanner - Copyright (c) 2012 Continuuity Inc. All rights reserved.
 */
package com.continuuity.examples.twitter;

import com.continuuity.api.common.Bytes;
import com.continuuity.api.data.OperationException;
import com.continuuity.api.flow.flowlet.AbstractFlowlet;
import com.continuuity.api.flow.flowlet.OutputEmitter;
import com.continuuity.api.flow.flowlet.Tuple;
import com.continuuity.api.flow.flowlet.builders.TupleBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TwitterProcessor extends AbstractFlowlet {
  private OutputEmitter<Map<String, Object>> output;

  private SortedCounterTable topHashTags;
  private SortedCounterTable topUsers;
  private CounterTable wordCounts;
  private CounterTable hashTagWordAssocs;

  public TwitterProcessor() {
    super("Processors");
  }

  public void process(OutputEmitter<Tweet> tweet) {
    String [] words = tweet.getText().split("\\s+");

    List<String> goodWords = new ArrayList<String>(words.length);
    for (String word : words) {
      if (word.length() < 3) continue;
      if (!word.equals(new String(word.getBytes()))) continue;
      goodWords.add(word.toLowerCase());
    }
    // Split tweet into individual words
    for (String word : goodWords) {
      if (word.startsWith("#")) {
        // Track top hash tags
        Long incrementedPrimaryCount = topHashTags.performPrimaryCounterIncrement(
          TwitterFlow.HASHTAG_SET, Bytes.toBytes(word), 1L);
        if (incrementedPrimaryCount != null) {
          Map<String, Object> outTuple=new HashMap<String, Object>();
          outTuple.put("name", word);
          outTuple.put("value", incrementedPrimaryCount);
          output.emit(outTuple);
        }
        // And for every hash tag, track word associations
        for (String corWord : goodWords) {
          if (corWord.startsWith("#")) continue;
//          System.out.println("Correlating (" + word + ") with (" + corWord + ")");
          try {
            hashTagWordAssocs.incrementCounterSet(word, corWord, 1L);
          } catch (OperationException e) {
            // This doesn't seem to work any way I try :(
            e.printStackTrace();
          }
        }
      } else {
        // Track word counts
        wordCounts.incrementCounterSet(TwitterFlow.WORD_SET, Bytes.toBytes(word), 1L);
      }
    }
    // Track top users
    Long incrementedPrimaryCount = topUsers.performPrimaryCounterIncrement(
      TwitterFlow.USER_SET, Bytes.toBytes(tweet.getUser()), 1L);
    if (incrementedPrimaryCount != null) {
      Map<String, Object> outTuple=new HashMap<String, Object>();
      outTuple.put("name", tweet.getUser());
      outTuple.put("value", incrementedPrimaryCount);
      output.emit(outTuple);
    }
  }
}
