/*
 * Copyright 2011 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.gitblit.GitBlit
import com.gitblit.Keys
import com.gitblit.models.RepositoryModel
import com.gitblit.models.UserModel
import com.gitblit.utils.JGitUtils
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceiveCommand.Result
import org.slf4j.Logger
import java.text.SimpleDateFormat

/**
 * Sample Gitblit Post-Receive Hook: telegram
 *
 * The Post-Receive hook is executed AFTER the pushed commits have been applied
 * to the Git repository.  This is the appropriate point to trigger an
 * integration build or to send a notification.
 * 
 * This script is only executed when pushing to *Gitblit*, not to other Git
 * tooling you may be using.
 * 
 * If this script is specified in *groovy.postReceiveScripts* of gitblit.properties
 * or web.xml then it will be executed by any repository when it receives a
 * push.  If you choose to share your script then you may have to consider
 * tailoring control-flow based on repository access restrictions.
 *
 * Scripts may also be specified per-repository in the repository settings page.
 * Shared scripts will be excluded from this list of available scripts.
 * 
 * This script is dynamically reloaded and it is executed within it's own
 * exception handler so it will not crash another script nor crash Gitblit.
 * 
 * Bound Variables:
 *  gitblit			Gitblit Server	 			com.gitblit.GitBlit
 *  repository		Gitblit Repository			com.gitblit.models.RepositoryModel
 *  receivePack		JGit Receive Pack			org.eclipse.jgit.transport.ReceivePack
 *  user			Gitblit User				com.gitblit.models.UserModel
 *  commands		JGit commands 				Collection<org.eclipse.jgit.transport.ReceiveCommand>
 *	url				Base url for Gitblit		String
 *  logger			Logs messages to Gitblit 	org.slf4j.Logger
 *  clientLogger	Logs messages to Git client	com.gitblit.utils.ClientLogger
 *
 * Accessing Gitblit Custom Fields:
 *   def myCustomField = repository.customFields.myCustomField
 *  
 */
// Indicate we have started the script
logger.info("telegram hook triggered by ${user.username} for ${repository.name}")

// define your telegram default url here or set groovy.telegramUrl in 
// gitblit.properties or web.xml
def telegramUrl = gitblit.getString('groovy.telegramUrl', 'https://api.telegram.org')

// define your telegram bot API token, telegram chat_id or set groovy.telegramBotToken, groovy.telegramChatId in
// gitblit.properties or web.xml

def botToken = gitblit.getString('groovy.telegramBotToken', 'your_bot_token')
def chatId = gitblit.getString('groovy.telegramChatId', 'your_chat_id')

// whether to remove .git suffix from repository name
// may be defined in gitblit.properties or web.xml


// define the summary and commit urls
def repo = repository.name
def summaryUrl
def commitUrl

if (gitblit.getBoolean(Keys.web.mountParameters, true)) {
	repo = repo.replace('/', gitblit.getString(Keys.web.forwardSlashCharacter, '/')).replace('/', '%2F')
	summaryUrl = url + "/summary/$repo"
	commitUrl = url + "/commit/$repo/"
} else {
	summaryUrl = url + "/summary?r=$repo"
	commitUrl = url + "/commit?r=$repo&h="
}

Repository r = gitblit.getRepository(repository.name)

// construct a simple text summary of the changes contained in the push
def branchBreak = '>---------------------------------------------------------------\n'
def commitBreak = '\n\n ----\n'
def commitCount = 0
def changes = ''
SimpleDateFormat df = new SimpleDateFormat(gitblit.getString(Keys.web.datetimestampLongFormat, 'EEEE, MMMM d, yyyy h:mm a z'))
def table = { "\n ${JGitUtils.getDisplayName(it.authorIdent)}\n ${df.format(JGitUtils.getCommitDate(it))}\n\n $it.shortMessage\n\n $commitUrl$it.id.name" }
for (command in commands) {
	def ref = command.refName
	def refType = 'branch'
	if (ref.startsWith('refs/heads/')) {
		ref  = command.refName.substring('refs/heads/'.length())
	} else if (ref.startsWith('refs/tags/')) {
		ref  = command.refName.substring('refs/tags/'.length())
		refType = 'tag'
	}
		
	switch (command.type) {
		case ReceiveCommand.Type.CREATE:
			def commits = JGitUtils.getRevLog(r, command.oldId.name, command.newId.name).reverse()
			commitCount += commits.size()
			// new branch
			changes += "\n$branchBreak new $refType $ref created ($commits.size commits)\n$branchBreak"
			changes += commits.collect(table).join(commitBreak)
			changes += '\n'
			break
		case ReceiveCommand.Type.UPDATE:
			def commits = JGitUtils.getRevLog(r, command.oldId.name, command.newId.name).reverse()
			commitCount += commits.size()
			// fast-forward branch commits table
			changes += "\n$branchBreak $ref $refType updated ($commits.size commits)\n$branchBreak"
			changes += commits.collect(table).join(commitBreak)
			changes += '\n'
			break
		case ReceiveCommand.Type.UPDATE_NONFASTFORWARD:
			def commits = JGitUtils.getRevLog(r, command.oldId.name, command.newId.name).reverse()
			commitCount += commits.size()
			// non-fast-forward branch commits table
			changes += "\n$branchBreak $ref $refType updated [NON fast-forward] ($commits.size commits)\n$branchBreak"
			changes += commits.collect(table).join(commitBreak)
			changes += '\n'
			break
		case ReceiveCommand.Type.DELETE:
			// deleted branch/tag
			changes += "\n$branchBreak $ref $refType deleted\n$branchBreak"
			break
		default:
			break
	}
}
// close the repository reference
r.close()

// tell Gitblit to send the message (Gitblit filters duplicate addresses)
def text = java.net.URLEncoder.encode("$user.username pushed $commitCount commits => $summaryUrl\n$changes", "UTF-8")


// define the trigger url
def triggerUrl = "$telegramUrl/$botToken/sendMessage?chat_id=$chatId&text=$text"

logger.info("trigger url : $triggerUrl")
logger.info("trigger text : $text")

// // trigger the build
def _url = new URL(triggerUrl)

//send get request
def _con = _url.openConnection()

logger.info("telegram response code: ${_con.responseCode}")
