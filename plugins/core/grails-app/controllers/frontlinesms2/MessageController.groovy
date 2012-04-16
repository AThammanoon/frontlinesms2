package frontlinesms2

import grails.util.GrailsConfig
import grails.converters.*

class MessageController {
	static allowedMethods = [save: "POST", update: "POST", delete: "POST", archive: "POST"]

	def messageSendService
	def fmessageInfoService
	def trashService
	boolean viewingArchive = params.controller=='archive' ? true : false

	def bobInterceptor = {
		params.sort = params.sort ?: 'date'
		params.order = params.order ?: 'desc'
		params.starred = params.starred ? params.starred.toBoolean() : false
		params.failed = params.failed ? params.failed.toBoolean() : false
		params.max = params.max ?: GrailsConfig.config.grails.views.pagination.max
		params.offset  = params.offset ?: 0
		return true
	}
	def beforeInterceptor = bobInterceptor
	
	def index = {
		params.sort = 'date'
		redirect(action:'inbox', params:params)
	}
	
	def getNewMessageCount = {
		def section = params.messageSection
		if(!params.ownerId && section != 'trash') {
			def messageCount = [totalMessages:[Fmessage."$section"().count()]]
			render messageCount as JSON
		} else if(section == 'activity') {
			def messageCount = [totalMessages:[Activity.get(params.ownerId)?.getActivityMessages()?.count()]]
			render messageCount as JSON
		} else if(section == 'folder') {
			def messageCount = [totalMessages:[Folder.get(params.ownerId)?.getFolderMessages()?.count()]]
			render messageCount as JSON
		} else
			render ""
	}
	
	def getShowModel(messageInstanceList) {
		def messageInstance = (params.messageId) ? Fmessage.get(params.messageId) : messageInstanceList ? messageInstanceList[0]:null
		if (messageInstance && !messageInstance.read) {
			messageInstance.read = true
			messageInstance.save()
		}
		def checkedMessageCount = params.checkedMessageList?.tokenize(',')?.size()
		def selectedMessageList = params.checkedMessageList?: ',' + messageInstance?.id + ','
		[messageInstance: messageInstance,
				checkedMessageCount: checkedMessageCount,
				checkedMessageList: selectedMessageList,
				activityInstanceList: Activity.findAllByArchivedAndDeleted(viewingArchive, false),
				folderInstanceList: Folder.findAllByArchivedAndDeleted(viewingArchive, false),
				messageCount: Fmessage.countAllMessages(params),
				hasFailedMessages: Fmessage.hasFailedMessages(),
				failedDispatchCount: (messageInstance && messageInstance.hasFailed) ? Dispatch.findAllByMessageAndStatus(messageInstance, DispatchStatus.FAILED).size() : 0]
	}

	def inbox = {
		def messageInstanceList = Fmessage.inbox(params.starred, viewingArchive)
		render view:'../message/standard',
					model:[messageInstanceList: messageInstanceList.list(params),
							messageSection: 'inbox',
							messageInstanceTotal: messageInstanceList.count()] << getShowModel()
	}

	def sent = {
		def messageInstanceList = Fmessage.sent(params.starred, viewingArchive)
		render view:'../message/standard', model:[messageSection: 'sent',
				messageInstanceList: messageInstanceList.list(params),
				messageInstanceTotal: messageInstanceList.count()] << getShowModel()
	}

	def pending = {
		def messageInstanceList = Fmessage.pending(params.failed)
		render view:'standard', model:[messageInstanceList: messageInstanceList.list(params),
				messageSection: 'pending',
				messageInstanceTotal: messageInstanceList.count()] << getShowModel()
	}
	
	def trash = {
		def trashInstance
		def trashInstanceList
		def messageInstanceList
		params.sort = (params.sort && params.sort != 'date') ?: "dateCreated"
		if(params.id) {
			def setTrashInstance = { obj ->
				if(obj.objectType == "frontlinesms2.Fmessage") {
					params.messageId = obj.linkId
				} else {
					trashInstance = obj.link
				}
			}
			setTrashInstance(Trash.findById(params.id))
		}
		
		if(params.starred) {
			messageInstanceList = Fmessage.deleted(params.starred)
		} else {
			trashInstanceList =  Trash.list(params)
		}
		render view:'standard', model:[trashInstanceList:trashInstanceList,
					messageInstanceList: messageInstanceList?.list(params),
					messageSection: 'trash',
					messageInstanceTotal: Trash.count(),
					ownerInstance: trashInstance] << getShowModel()
	}

	def poll = { redirect(action: 'activity', params: params) }
	def announcement = { redirect(action: 'activity', params: params) }
	def autoreply = { redirect(action: 'activity', params: params) }
	def activity = {
		def activityInstance = Activity.get(params.ownerId.toLong())
		if (activityInstance) {
			def messageInstanceList = activityInstance.getActivityMessages(params.starred, true)
			def sentMessageCount = 0
			def sentDispatchCount = 0
			Fmessage.findAllByMessageOwnerAndInbound(activityInstance, false).each {
				sentDispatchCount += it.dispatches.size()
				sentMessageCount++
			}
			render view:"../message/${activityInstance.type == 'poll' ? 'poll' : 'standard'}",
				model:[messageInstanceList: messageInstanceList?.list(params),
						messageSection: 'activity',
						messageInstanceTotal: messageInstanceList?.count(),
						ownerInstance: activityInstance,
						viewingMessages: viewingArchive ? params.viewingMessages : null,
						pollResponse: activityInstance?.type == 'poll' ? activityInstance.responseStats as JSON : null,
						sentMessageCount: sentMessageCount,
						sentDispatchCount: sentDispatchCount] << getShowModel()
		} else {
			flash.message = "Activity could not be found"
			redirect(action: 'inbox')
		}
	}
	
	def folder = {
		def folderInstance = Folder.get(params.ownerId)
		if (folderInstance) {
			def messageInstanceList = folderInstance?.getFolderMessages(params.starred)
			if (params.flashMessage) { flash.message = params.flashMessage }
			render view:'../message/standard', model:[messageInstanceList: messageInstanceList.list(params),
						messageSection: 'folder',
						messageInstanceTotal: messageInstanceList.count(),
						ownerInstance: folderInstance,
						viewingMessages: viewingArchive ? params.viewingMessages : null] << getShowModel()
		} else {
			flash.message = "Folder could not be found"
			redirect(action: 'inbox')
		}
	}

	def send = {
		def message = messageSendService.createOutgoingMessage(params)
		messageSendService.send(message)
		flash.message = "Message has been queued to send to " + message.dispatches*.dst?.join(", ")
		render(text: flash.message)
	}
	
	def retry = {
		def dst = []
		def failedMessageIdList = params.checkedMessageList?.tokenize(',') ?: [params.messageId]
		failedMessageIdList.each { id ->
			withFmessage id, {messageInstance ->
				messageInstance.dispatches.each { 
					if(it.status == DispatchStatus.FAILED) { 
						dst << Contact.findByMobile(it.dst)?.name ?: it.dst
					}
				}
				messageSendService.retry(messageInstance)
			}
		}
		
		flash.message = "Message has been queued to send to " + dst.flatten().join(", ")
		redirect (controller: "message", action: 'pending')
	}

	def delete = {
		def messageIdList = params.checkedMessageList ? params.checkedMessageList.tokenize(',') : [params.messageId]
		messageIdList.each { id ->
			withFmessage id, {messageInstance ->
				messageInstance.isDeleted = true
				new Trash(identifier:messageInstance.displayName, message:messageInstance.text, objectType:messageInstance.class.name, linkId:messageInstance.id).save()
				messageInstance.save()
			}
		}
		flash.message = "${message(code: 'default.deleted.message', args: [message(code: 'message.label', default: ''), messageIdList.size() + ' message(s)'])}"
		if(params.messageSection == 'result')
			redirect(controller: 'search', action: 'result', params: [searchId: params.searchId])
		else
			redirect(controller: params.controller, action: params.messageSection, params: [ownerId: params.ownerId, starred: params.starred, failed: params.failed])
	}
	
	def archive = {
		def messageIdList = params.checkedMessageList ? params.checkedMessageList.tokenize(',') : [params.messageId]
		def listSize = messageIdList.size();
		messageIdList.each { id ->
			withFmessage id, { messageInstance ->
				if(!messageInstance.messageOwner) {
					messageInstance.archived = true
					messageInstance.save()
				} else {
					listSize--
				}
			}
		}
		flash.message = "${message(code: 'default.archived.message', args: [message(code: 'message.label', default: ''), listSize + ' message(s)'])}"
		if(params.messageSection == 'result') {
			redirect(controller: 'search', action: 'result', params: [searchId: params.searchId, messageId: params.messageId])
		} else {
			redirect(controller: 'message', action: params.messageSection, params: [ownerId: params.ownerId])
		}
	}
	
	def unarchive = {
		def messageIdList = params.checkedMessageList ? params.checkedMessageList.tokenize(',') : [params.messageId]
		def listSize = messageIdList.size();
		messageIdList.each { id ->
			withFmessage id, {messageInstance ->
				if(!messageInstance.messageOwner) {
					messageInstance.archived = false
					messageInstance.save(failOnError: true, flush: true)
				} else {
					listSize--
				}
			}
		}
		flash.message = "${message(code: 'default.unarchived.message', args: [message(code: 'message.label', default: ''), listSize + ' message(s)'])}"
		if(params.messageSection == 'result')
			redirect(controller: 'search', action: 'result', params: [searchId: params.searchId, messageId: params.messageId])
		else
			redirect(controller: 'archive', action: params.messageSection, params: [ownerId: params.ownerId])
	}

	def move = {
		def messageIdList = params.messageId.tokenize(',')
		messageIdList.each { id ->
			withFmessage id, {messageInstance ->
				if (messageInstance.isDeleted == true)
					messageInstance.isDeleted = false
				if(Trash.findByLinkId(messageInstance.id))
					Trash.findByLinkId(messageInstance.id).delete(flush:true)
				if (params.messageSection == 'activity') {
					def activity = Activity.get(params.ownerId)
					activity.addToMessages(messageInstance)
					activity.save()
					if(activity && activity.autoreplyText)
						redirect(controller: "activty instanceof frontlinesms2.Poll ? 'poll' : 'autoreply'", action: 'sendReply', params: [ownerId: activity.id, messageId: messageInstance.id])
				} else if (params.messageSection == 'folder' || params.messageSection == 'radioShow') {
					MessageOwner.get(params.ownerId).addToMessages(messageInstance).save()
				} else {
					messageInstance.with {
						messageOwner?.removeFromMessages(messageInstance)
						messageOwner = null
						messageOwner?.save()
						save()
					}
				}
			}
		}
		flash.message = "${message(code: 'default.updated.message', args: [message(code: 'message.label', default: ''), messageIdList.size() + ' message(s)'])}"
		render ""
	}

	def changeResponse = {
		def messageIdList = params.messageIdList?.tokenize(',') ?: [params.messageId]
		def responseInstance = PollResponse.get(params.responseId)
		messageIdList.each { id ->
			withFmessage id, { messageInstance ->
				responseInstance.poll.removeFromMessages(messageInstance)
				responseInstance.addToMessages(messageInstance)
				responseInstance.poll.save()
			}
		}
		flash.message = "${message(code: 'default.updated.message', args: [message(code: 'message.label', default: 'Fmessage'), 'message(s)'])}"
		render ""
	}

	def changeStarStatus = {
		withFmessage { messageInstance ->
			messageInstance.starred =! messageInstance.starred
			messageInstance.save(failOnError: true, flush: true)
			Fmessage.get(params.messageId).messageOwner?.refresh()
            params.remove('messageId')
			render(text: messageInstance.starred ? "starred" : "unstarred")
		}
	}
	
	def showRecipients = {
		def groupList = []
		def contactList = []
		def addressList = []
		def finalAddressList = []
		
		def message = Fmessage.get(params.messageId) ?: null
		if(message) {
			addressList = message.dispatches
			Group.getAll().each {
				def groupAddressList = it.getAddresses()
				if (groupAddressList != [] && addressList.dst.containsAll(groupAddressList)) {
					groupList += it
				}
			}
			message.dispatches.each {
				Contact c = Contact.findByMobile(it.dst)
				if(c) {
					contactList += "${c.name} (${it.status})"
					addressList -= it
				}
			}
		}
		addressList.each {
			finalAddressList += "${it.dst} (${it.status})"
		}
		contactList = contactList - null
		[groupList: groupList,
			contactList: contactList,
			addressList: finalAddressList]
	}

	def confirmEmptyTrash = { }
	
	def emptyTrash = {
		trashService.emptyTrash()
		redirect(action: 'inbox')
	}
	
	def getUnreadMessageCount = {
		render text: Fmessage.countUnreadMessages(), contentType:'text/plain'
	}
	
	def getSendMessageCount = {	
		def messageInfo
		def message = params.message ?: ''
		if(message)	{ 
			messageInfo = fmessageInfoService.getMessageInfos(message)
			def messageCount = messageInfo.partCount > 1 ? "${messageInfo.partCount} SMS messages": "1 SMS message"
			render text: "Characters remaining ${messageInfo.remaining} ($messageCount)", contentType:'text/plain'
		} else {
			render text: "Characters remaining 160 (1 SMS message)", contentType:'text/plain'
		}
		
	}
	
	private def withFmessage(messageId = params.messageId, Closure c) {
			def m = Fmessage.get(messageId.toLong())
			if(m) c.call(m)
			else render(text: "Could not find message with id ${params.messageId}") // TODO handle error state properly
	}
}
