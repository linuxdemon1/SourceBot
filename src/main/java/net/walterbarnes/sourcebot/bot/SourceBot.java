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

package net.walterbarnes.sourcebot.bot;

import com.google.gson.JsonObject;
import net.walterbarnes.sourcebot.bot.command.CommandHandler;
import net.walterbarnes.sourcebot.bot.thread.InputThread;
import net.walterbarnes.sourcebot.common.cli.Cli;
import net.walterbarnes.sourcebot.common.config.Configuration;
import net.walterbarnes.sourcebot.common.crash.CrashReport;
import net.walterbarnes.sourcebot.common.reference.Constants;
import net.walterbarnes.sourcebot.common.tumblr.Tumblr;
import net.walterbarnes.sourcebot.common.util.LogHelper;
import net.walterbarnes.sourcebot.web.ConfigServer;
import net.walterbarnes.sourcebot.web.IndexHandler;
import net.walterbarnes.sourcebot.web.auth.CallbackHandler;
import net.walterbarnes.sourcebot.web.auth.ConnectHandler;
import net.walterbarnes.sourcebot.web.blog.BlogManagerHandler;
import net.walterbarnes.sourcebot.web.search.RuleManagerHandler;
import org.scribe.exceptions.OAuthConnectionException;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SourceBot
{
	/**
	 * Static link to current SourceBot instance
	 */
	private static final SourceBot currentBot = new SourceBot();

	/**
	 * Default config file name, in the future, this may be overridden via command line arguments
	 */
	public final String confName = "SourceBot.json";
	public final Map<String, SearchThread> threads = new HashMap<>();
	private final Logger logger = Logger.getLogger(SourceBot.class.getName());
	private final InputThread inputThread = new InputThread();
	public volatile boolean running = true;
	public Thread currentThread;
	public Tumblr client;
	/**
	 * Default configuration directory, can be overridden via command-line arguments
	 */
	public File confDir = new File(System.getProperty("user.home"), ".sourcebot");

	private Configuration conf;
	private Connection conn;
	private CommandHandler commandHandler;

	public static void main(String[] args)
	{
		try
		{
			// Initialize and configure the root logger
			LogHelper.init();

			// Start new user command handler
			currentBot.commandHandler = new CommandHandler();

			Thread t = new Thread(currentBot.inputThread, "Console Input Handler");
			t.setDaemon(true);
			t.start();

			// Parse and handle command line arguments
			new Cli(args).parse();

			// Check existence of config directory
			if (!currentBot.confDir.exists())
			{
				// Attempt to create the directory
				if (!currentBot.confDir.mkdirs())
				{
					throw new RuntimeException("Unable to create config dir");
				}
			}

			// Create config instance
			currentBot.conf = new Configuration(currentBot.confDir.getAbsolutePath(), currentBot.confName);

			// If the config file doesn't exists, run the install process
			if (!currentBot.conf.exists())
			{
				Install.install(currentBot.confDir.getAbsolutePath(), currentBot.confName);
				System.exit(0);
			}

			// Load/read config
			currentBot.conf.init();

			Constants.load(currentBot.conf);

			ConfigServer cs = new ConfigServer(8087);
			cs.addPage("/connect", new ConnectHandler(Constants.getConsumerKey(), Constants.getConsumerSecret()));
			cs.addPage("/callback", new CallbackHandler());
			cs.addPage("/rules", new RuleManagerHandler());
			cs.addPage("/blogs", new BlogManagerHandler());
			cs.addPage("/", new IndexHandler());
			cs.start();

			// Run main thread
			currentBot.run();
		}
		catch (Throwable throwable)
		{
			CrashReport.displayCrashReport(new CrashReport("Unexpected error", throwable));
		}
		finally
		{
			// Shutdown sequence
			if (currentBot.currentThread != null)
			{
				currentBot.currentThread.interrupt();
			}
			if (currentBot.conn != null)
			{
				try
				{
					currentBot.conn.close();
				}
				catch (SQLException e)
				{
					currentBot.logger.warning("Error occurred on closing connection to database");
				}
			}
		}
	}

	private void run() throws SQLException, ClassNotFoundException, IllegalAccessException, InstantiationException
	{
		Class.forName("org.postgresql.Driver").newInstance();

		this.client = new Tumblr(Constants.getConsumerKey(), Constants.getConsumerSecret(), Constants.getToken(), Constants.getTokenSecret());

		Configuration dbCat = conf.getCategory("db", new JsonObject());

		String dbHost = dbCat.getString("host", "");
		String dbPort = dbCat.getString("port", "");
		String dbUser = dbCat.getString("user", "");
		String dbPass = dbCat.getString("pass", "");
		String dbName = dbCat.getString("dbName", "");
		if (conf.hasChanged()) conf.save();

		final Connection conn = DriverManager.getConnection(String.format("jdbc:postgresql://%s:%s/%s", dbHost, dbPort, dbName),
				dbUser, dbPass);

		Thread botThread = new Thread()
		{
			public void run()
			{
				PreparedStatement getBlogs = null;
				try
				{
					getBlogs = conn.prepareStatement("SELECT url,active,adm_active FROM blogs ORDER BY id;",
							ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);

					ResultSet rs = getBlogs.executeQuery();

					long queryTime = System.currentTimeMillis();

					while (running)
					{
						try
						{
							if ((System.currentTimeMillis() - queryTime) > 60000)
							{
								rs = getBlogs.executeQuery();
								queryTime = System.currentTimeMillis();
							}

							rs.beforeFirst();
							while (rs.next() && running)
							{
								String url = rs.getString("url");
								boolean active = rs.getBoolean("active");
								boolean adm_active = rs.getBoolean("adm_active");
								if (active && adm_active)
								{
									if (!threads.containsKey(url))
									{
										SearchThread bt = new SearchThread(client, url, conn);
										threads.put(url, bt);
									}
									logger.fine("Running Thread for " + url);
									long start = System.currentTimeMillis();
									currentThread = new Thread(threads.get(url));
									currentThread.start();
									currentThread.join();
									logger.fine("Took " + (System.currentTimeMillis() - start) + " ms");
								}
							}
						}
						catch (OAuthConnectionException e)
						{
							logger.log(Level.SEVERE, e.getMessage(), e);
						}
						catch (InterruptedException ignored)
						{
							Thread.currentThread().interrupt();
						}
					}
				}
				catch (Throwable throwable)
				{
					CrashReport.displayCrashReport(new CrashReport("Unexpected error", throwable));
				}
				finally
				{

					try
					{
						if (getBlogs != null)
						{
							getBlogs.close();
						}
					}
					catch (SQLException e)
					{
						logger.log(Level.SEVERE, e.getMessage(), e);
					}
				}
			}
		};
		botThread.start();
	}

	/**
	 * Gets the current instance of the bot, or creates one if none is set
	 *
	 * @return the bot instance
	 */
	public static SourceBot getCurrentBot()
	{
		return currentBot;
	}

	/**
	 * Gets the current bots CommandHandler instance, or creates one if none is set
	 *
	 * @return the CommandHandler instance
	 */
	public CommandHandler getCommandHandler()
	{
		return commandHandler;
	}
}