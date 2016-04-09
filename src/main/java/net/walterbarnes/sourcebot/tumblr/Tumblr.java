/*
 * Copyright (c) 2016.
 * This file is part of SourceBot.
 *
 * SourceBot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SourceBot is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SourceBot.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.walterbarnes.sourcebot.tumblr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.tumblr.jumblr.JumblrClient;
import com.tumblr.jumblr.responses.PostDeserializer;
import com.tumblr.jumblr.types.AnswerPost;
import com.tumblr.jumblr.types.Post;
import org.scribe.builder.ServiceBuilder;
import org.scribe.builder.api.TumblrApi;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.oauth.OAuthService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings ({"WeakerAccess", "unused"})
public class Tumblr extends JumblrClient
{
	private final String consumerKey;
	private final String consumerSecret;
	private final Token token;

	public Tumblr(String consumerKey, String consumerSecret, String token, String tokenSecret)
	{
		super(consumerKey, consumerSecret);
		setToken(token, tokenSecret);
		this.consumerKey = consumerKey;
		this.consumerSecret = consumerSecret;
		this.token = new Token(token, tokenSecret);
	}

	public List<AnswerPost> getAsks(String blogName)
	{
		int offset = 0;
		List<AnswerPost> asks = new ArrayList<>();
		List<Post> subs;
		while ((subs = blogSubmissions(blogName, offset)).size() > 0)
		{
			for (Post post : subs)
			{
				offset++;
				if (post.getType().getValue().equals("answer")) asks.add((AnswerPost) post);
			}
		}
		return asks;
	}

	public List<Post> blogSubmissions(String blogName, int offset)
	{
		Map<String, Object> params = new HashMap<>();
		params.put("offset", offset);
		return blogSubmissions(blogName, params);
	}

	public List<Post> getQueuedPosts(String blogName)
	{
		long offset = 0;
		List<Post> queue;
		ArrayList<Post> out = new ArrayList<>();
		while ((queue = blogQueuedPosts(blogName, offset)).size() > 0)
			for (Post post : queue)
			{
				out.add(post);
				offset++;
			}
		return out;
	}

	public List<Post> blogQueuedPosts(String blogName, long offset)
	{
		Map<String, Object> params = new HashMap<>();
		params.put("offset", offset);
		return blogDraftPosts(blogName, params);
	}

	public List<Post> getDrafts(String blogName)
	{
		long before = 0;
		List<Post> drafts;
		ArrayList<Post> out = new ArrayList<>();
		while ((drafts = blogDraftPosts(blogName, before)).size() > 0)
			for (Post post : drafts)
			{
				out.add(post);
				before = post.getId();
			}
		return out;
	}

	/**
	 * Retrives a blogs drafts
	 *
	 * @param blogName Blog to retrieve posts from
	 * @param before   Retrieve posts before this id
	 * @return A List of posts from the blogs drafts
	 */
	public List<Post> blogDraftPosts(String blogName, long before)
	{
		Map<String, Object> params = new HashMap<>();
		params.put("before_id", before);
		return blogDraftPosts(blogName, params);
	}

	public List<Post> getBlogPosts(String blogName)
	{
		List<Post> posts;
		ArrayList<Post> out = new ArrayList<>();
		while ((posts = blogPosts(blogName, out.size())).size() > 0)
			for (Post post : posts)
			{
				out.add(post);
			}
		return out;
	}

	public List<Post> blogPosts(String blogName, long before)
	{
		Map<String, Object> params = new HashMap<>();
		params.put("offset", before);
		return blogPosts(blogName, params);
	}

	@SuppressWarnings ("Duplicates")
	public List<String> getRawTaggedPosts(String tag)
	{
		OAuthService service = new ServiceBuilder().
				provider(TumblrApi.class).
				apiKey(consumerKey).apiSecret(consumerSecret).
				build();
		String path = "/tagged";
		Map<String, Object> map = new HashMap<>();
		map.put("api_key", consumerKey);
		map.put("tag", tag);
		OAuthRequest request = getRequestBuilder().constructGet(path, map);
		if (token != null)
		{
			service.signRequest(token, request);
		}
		Gson gsonBuilder = new Gson();
		//Gson gsonBuilder = new GsonBuilder().setPrettyPrinting().create();
		JsonParser parser = new JsonParser();
		Response response = request.send();

		System.out.println(gsonBuilder.toJson(parser.parse(response.getBody())
				.getAsJsonObject().get("response").getAsJsonObject().getAsJsonArray("posts").get(0)));
		Gson gson = new GsonBuilder().
				registerTypeAdapter(Post.class, new PostDeserializer()).
				create();
		return null;
	}
}
