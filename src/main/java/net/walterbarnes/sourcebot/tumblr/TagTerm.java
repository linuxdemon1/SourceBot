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
import net.walterbarnes.sourcebot.BotThread;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TagTerm implements SearchTerm
{
	private final Tumblr client;
	private final Logger logger;
	private BotThread.Blog blog;
	private String tag;
	private PostCache cache = new PostCache(120 * 60 * 1000);
	private int lastPostCount = 0;

	public TagTerm(String tag, Tumblr client, BotThread.Blog blog, Logger logger)
	{
		this.tag = tag;
		this.client = client;
		this.blog = blog;
		this.logger = logger;
	}

	public Map<Post, String> getPosts(List<String> blogBlacklist, List<String> tagBlacklist, String[] requiredTags,
									  String[] postType, String postSelect, int sampleSize, boolean active) throws SQLException
	{
		List<Long> postBlacklist = blog.getPosts();

		int postNum = lastPostCount > 0 ? lastPostCount : (sampleSize == 0 ? blog.getSampleSize() : sampleSize);
		String[] type = postType == null ? blog.getPostType() : postType;

		int searched = 0;

		long lastTime = System.currentTimeMillis() / 1000;
		long start = System.currentTimeMillis();

		cache.validate();

		Map<Post, String> out = new HashMap<>();

		for (Object obj : cache)
		{
			if (obj instanceof Post)
			{
				Post p = (Post) obj;
				out.put(p, String.format("tag:%s", tag));
			}
			else
			{
				throw new RuntimeException("Non-post object in post cache");
			}
		}

		logger.info("Searching tag " + tag);
		while (out.size() < postNum)
		{
			HashMap<String, Object> options = new HashMap<>();

			options.put("before", lastTime);

			List<Post> posts;
			try
			{
				if (tag.contains(","))
				{ posts = client.tagged(tag.split(",\\s?")[0], options); }
				else
				{ posts = client.tagged(tag, options); }
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
				List<String> types = new ArrayList<>(Arrays.asList(type == null ? blog.getPostType() : type));
				if (types.contains(post.getType().getValue()))
				{
					if (blogBlacklist.contains(post.getBlogName()) || postBlacklist.contains(post.getId())) continue;

					if (requiredTags != null)
					{
						for (String rt : requiredTags)
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

					if (cache.addPost(post)) out.put(post, String.format("tag:%s", tag));
				}
			}
		}
		long end = System.currentTimeMillis() - start;

		logger.info(String.format("Searched tag %s, selected %d posts out of %d searched (%f%%), took %d ms", tag,
				out.size(), searched, ((double) (((float) out.size()) / ((float) searched)) * 100), end));

		blog.addStat("tag", tag, (int) end, searched, out.size());
		lastPostCount = out.size();
		return out;
	}

	public PostCache getCache()
	{
		return cache;
	}

	@Override
	public String getSearchTerm()
	{
		return "tag:" + tag;
	}
}