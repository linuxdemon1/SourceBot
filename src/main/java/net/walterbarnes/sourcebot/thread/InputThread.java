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

package net.walterbarnes.sourcebot.thread;

import net.walterbarnes.sourcebot.SourceBot;
import net.walterbarnes.sourcebot.command.CommandHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class InputThread implements Runnable
{
	@Override
	public void run()
	{
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		CommandHandler ch = SourceBot.getCurrentBot().getCommandHandler();
		ch.init();
		String input;
		do
		{
			try
			{
				while (!br.ready())
				{
					Thread.sleep(200);
				}
				input = br.readLine();
				ch.addPendingCommand(input);
				ch.executePendingCommands();
			}
			catch (InterruptedException ignored)
			{
				Thread.currentThread().interrupt();
				break;
			}
			catch (IOException ignored) {}
		}
		while (true);
	}
}
