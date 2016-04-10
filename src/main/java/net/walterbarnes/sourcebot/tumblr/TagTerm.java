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

import com.tumblr.jumblr.exceptions.JumblrException;
import com.tumblr.jumblr.types.Post;
import net.walterbarnes.sourcebot.SearchThread;
import net.walterbarnes.sourcebot.search.SearchInclusion;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TagTerm implements SearchTerm
{
	private static final Logger logger = Logger.getLogger(TagTerm.class.getName());

	private final String term;
	private final Tumblr client;
	private final SearchThread.Blog blog;
	private final PostCache cache = new PostCache(120 * 60 * 1000);
	private int lastPostCount = 0;

	public TagTerm(String term, Tumblr client, SearchThread.Blog blog)
	{
		this.term = term;
		this.client = client;
		this.blog = blog;
	}

	@Override
	public PostCache getCache()
	{
		return cache;
	}

	@Override
	public String getSearchTerm()
	{
		return "tag:" + term;
	}

	@SuppressWarnings ("Duplicates")
	@Override
	public Map<Post, String> getPosts(List<String> blogBlacklist, List<String> tagBlacklist, SearchInclusion rule) throws SQLException
	{
		List<Long> postBlacklist = blog.getPosts();

		int postNum = lastPostCount > 0 ? lastPostCount : (rule.getSampleSize() == 0 ? blog.getSampleSize() : rule.getSampleSize());
		String[] types = rule.getPostType() == null ? blog.getPostType() : rule.getPostType();

		long lastTime = System.currentTimeMillis() / 1000;
		int searched = 0;

		long start = System.currentTimeMillis();

		cache.validate();

		Map<Post, String> out = new HashMap<>();

		for (Object obj : cache)
		{
			if (obj instanceof Post)
			{
				Post p = (Post) obj;
				out.put(p, String.format("tag:%s", term));
			}
			else
			{
				throw new RuntimeException("Non-post object in post cache");
			}
		}

		logger.info("Searching tag " + term);
		while (out.size() < postNum)
		{
			HashMap<String, Object> options = new HashMap<>();

			options.put("before", lastTime);

			List<Post> posts;
			try
			{
				posts = client.tagged(term, options);
			}
			catch (JumblrException e)
			{
				logger.log(Level.SEVERE, e.getMessage(), e);
				continue;
			}

			if (posts.size() == 0 || posts.isEmpty()) break;

			loop:
			for (Post post : posts)
			{
				searched++;
				lastTime = post.getTimestamp();
				if (Arrays.asList(types).contains(post.getType().getValue()))
				{
					if (blogBlacklist.contains(post.getBlogName()) || postBlacklist.contains(post.getId())) continue;

					if (rule.getRequiredTags() != null)
					{
						for (String rt : rule.getRequiredTags())
						{
							if (!post.getTags().contains(rt)) continue loop;
						}
					}
					else
					{
						for (String tag : tagBlacklist)
						{
							if (post.getTags().contains(tag)) continue loop;
						}
					}

					if (cache.addPost(post)) out.put(post, String.format("tag:%s", term));
				}
			}
		}
		long end = System.currentTimeMillis() - start;

		logger.info(String.format("Searched tag %s, selected %d posts out of %d searched (%f%%), took %d ms", term,
				out.size(), searched, ((double) (((float) out.size()) / ((float) searched)) * 100), end));

		blog.addStat("tag", term, (int) end, searched, out.size());
		lastPostCount = out.size();
		return out;
	}
}
