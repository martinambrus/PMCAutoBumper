package com.github.pmcautobumper;

import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.RefreshHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import org.apache.commons.logging.LogFactory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class PMCAutoBumper extends JavaPlugin {
	private long lastBump;
	private int bumpInterval;
	private WebClient webClient;
	// http://stackoverflow.com/questions/12057650/htmlunit-failure-attempted-immediaterefreshhandler-outofmemoryerror-use-wait
	private RefreshHandler rh = new RefreshHandler() {
		@Override
		public void handleRefresh(Page arg0, URL arg1, int arg2)
				throws IOException {
			// Do nothing
		}
	};

	public void onEnable() {
		enableWebClient();
		saveDefaultConfig();
		lastBump = getConfig().getLong("last-bump", 0);
		bumpInterval = getConfig().getInt("last-bump", 15);
		if (bumpInterval < 5) {
		    getLogger().log(Level.INFO, "A bump-interval of less than 5 minutes was found. Defaulting to 5 mins.");
		    bumpInterval = 5;
		}
		
		if (getConfig().getBoolean("autobump")) {
			Bukkit.getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
				@Override
				public void run() {
					attemptBump();
				}
			}, 10L, TimeUnit.MINUTES.toSeconds(bumpInterval) * 20);
		}
	}

	public boolean onCommand(final CommandSender sender, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("bump")) {
			if(!sender.hasPermission("pmcautobumper.admin")) {
				sender.sendMessage(ChatColor.RED+"You do not have permission to do this.");
				return true;
			}
			if (args.length > 1) {
				if (args[0].equalsIgnoreCase("reload")) {
					reloadConfig();
					sender.sendMessage(ChatColor.GREEN+"PMCAutoBumper config sucessfully reloaded.");
					return true;
				} else {
					return false;
				}
			}
			Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
				@Override
				public void run()
				{
					bumpWithConfigSettings(sender);
				}
			});
		}
		return true;
	}

	public void attemptBump() {
		if (TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - lastBump) >= 24) {
			getLogger().log(Level.INFO, "The server requires bumping.");
			SimpleDateFormat df = new SimpleDateFormat("HH");
			if (Integer.parseInt(df.format(new Date())) >= 15
					&& Integer.parseInt(df.format(new Date())) < 19) {
				if (bumpWithConfigSettings(Bukkit.getConsoleSender())) {
					lastBump = System.currentTimeMillis();
					getConfig().set("last-bump", lastBump);
					saveConfig();
				}
			} else {
				getLogger().log(Level.INFO, "However, it is not between 15:00 and 19:00.");
				getLogger().log(Level.INFO, "Server is waiting a day to return to normal schedule.");
			}
		} else {
			getLogger().log(Level.INFO, "The server does not require bumping.");
		}
	}

	private boolean bumpWithConfigSettings(CommandSender sender) {
		return bump(sender, getConfig().getString("username"), getConfig().getString("password"), getConfig().getString("server-page"));
	}

	/**
	 * Logs into PMC with the defined username and password, and attempts to bump the defined server page.
	 * It is HIGHLY recommended that you run this method asynchronously, as it WILL freeze the thread it's running in.
	 * @param sender		CommandSender to send updates to, may be null
	 * @param username		username to log in with
	 * @param password		password to log in with
	 * @param serverPage	server page to attempt to bump
	 * @return				whether or not the bump succeeded
	 */
	public boolean bump(CommandSender sender, String username, String password, String serverPage) {
		if (sender != null) {
			sender.sendMessage(ChatColor.GREEN+"Attempting to bump server...");
		}
		
		HtmlPage page;
		try {
			page = webClient.getPage("http://www.planetminecraft.com/account/sign_in/");
		} catch(IOException e)
		{
			e.printStackTrace();
			return false;
		}

		if (page.getTitleText().matches(".*Website is currently unreachable.*")) {
			if(sender != null)
				sender.sendMessage(ChatColor.RED+"PMC is currently offline.");
			return false;
		}

		if (sender != null) {
			sender.sendMessage(ChatColor.GREEN+"Connected to planet minecraft.");
		}

		try {
			HtmlForm form = page.getFirstByXPath("//*[@id='full_screen']/div/div/div/form");
			HtmlElement usernameElement = form.getInputByName("username");
			HtmlElement passwordElement = form.getInputByName("password");
			HtmlElement loginElement = form.getInputByName("login");

			usernameElement.type(username);
			passwordElement.type(password);
			page = loginElement.click();
		} catch (Exception e) {
			sender.sendMessage("Already logged in, or (less likely) PMC has changed login page format.");
			sender.sendMessage("Contact developer ONLY if this warning persists after 24 hours.");
		}

		HtmlElement errorElement = page.getFirstByXPath("/html/body//div[@class='error']");
		if (errorElement != null) {
			if (sender != null) {
				sender.sendMessage(ChatColor.RED+"Login failed.");
				sender.sendMessage(ChatColor.RED+errorElement.getTextContent());
			}
			return false;
		}

		if (sender != null) {
			sender.sendMessage(ChatColor.GREEN+"Logged into planet minecraft.");
		}

		String serverId = serverPage.replaceAll("\\D+", "");
		try {
			page = webClient.getPage("http://www.planetminecraft.com/account/manage/servers/"+serverId+"/#tab_log");
		} catch(IOException e) {
			e.printStackTrace();
			return false;
		}

		if (sender != null) {
			sender.sendMessage(ChatColor.GREEN+"Navigated to update page.");
		}

		try {
			HtmlElement bumpElement = (HtmlElement) page.getElementById("bump");
			bumpElement.click();
			if (sender != null) {
			    sender.sendMessage(ChatColor.GREEN+"Clicked bump button.");
			}
		} catch (Exception e) {
			if (sender != null) {
				sender.sendMessage(ChatColor.RED+"Failed. PMC claims server was bumped in the past 24 hours.");
				return false;
			}
		}

		if (sender != null) {
			sender.sendMessage(ChatColor.GREEN+"Server sucessfully bumped!");
		}
		return true;
	}

	private void enableWebClient() {
		// Arbitrary choice of browser
		webClient = new WebClient(BrowserVersion.FIREFOX_60);
		// This gives time for the JavaScript to load. If we don't allow it to load, clicking the bump button fails
		webClient.setAjaxController(new NicelyResynchronizingAjaxController());
		// Since we're giving time for JavaScript to load, we obviously want JavaScript enabled as well
		webClient.getOptions().setJavaScriptEnabled(true);
		// May or may not be necessary
		webClient.getOptions().setCssEnabled(true);
		webClient.getOptions().setRedirectEnabled(true);
		webClient.setRefreshHandler(rh);
		// HTMLUnit complains about PMC site design unless we tell it not to
		LogFactory.getFactory().setAttribute("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
		java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit").setLevel(Level.OFF);
		java.util.logging.Logger.getLogger("org.apache.commons.httpclient").setLevel(Level.OFF);
		webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
		webClient.getOptions().setThrowExceptionOnScriptError(false);
	}
}
