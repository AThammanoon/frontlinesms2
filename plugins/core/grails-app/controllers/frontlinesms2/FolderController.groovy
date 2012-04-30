package frontlinesms2

class FolderController {
	static allowedMethods = [save: "POST", update: "POST", delete: "POST"]
	
	def index = {
		 redirect(action: "create", params: params)
	}

	def create = {
		def folderInstance = new Folder()
		folderInstance.properties = params
		[folderInstance: folderInstance]
	}

	def save = {
		def folderInstance = new Folder(params)
		if (folderInstance.save(flush: true)) {
			flash.message = message(code: 'default.created.message', args: [message(code: message(code: 'folder.label'), default: 'Folder'), folderInstance.name])
			redirect(controller: "message", action:'inbox', params:[flashMessage: flash.message])
		} else {
			flash.message = "error"
			redirect(controller: "message", action:'inbox', params:[flashMessage: flash.message])
		}
	}

	def archive = {
		withFolder { folder ->
			folder.archive()
			
			if(folder.save()) {
				flash.message = message(code: 'folder.archived.successfully')
				redirect(controller: "message", action: "inbox")
			} else {
				// TODO give error and redirect
			}
		}
	}
	
	def unarchive = {
		withFolder { folder ->
			folder.unarchive()
			if(folder.save()) {
				flash.message = message(code: 'folder.unarchived.successfully')
				redirect(controller: "archive", action: "folderList")
			} else {
				// TODO show error and redirect somewhere sensible
			}
		}
	}

	def confirmDelete = {
		def folderInstance = Folder.get(params.id)
		render view: "../activity/confirmDelete", model: [ownerInstance: folderInstance]
	}
	
	def delete = {
		withFolder { folder ->
			folder.deleted = true
			new Trash(displayName:folder.name, displayDetail:"${folder.liveMessageCount}", objectClass:folder.class, objectId:folder.id).save(failOnError: true, flush: true)
			folder.save(failOnError: true, flush: true)
		}
		flash.message = message(code: 'folder.trashed')
		redirect(controller:"message", action:"inbox")
	}
	
	def restore = {
		withFolder { folder ->
			folder.deleted = false
			folder.save(failOnError: true, flush: true)
			Trash.findByLinkId(folder.id)?.delete()
		}
		flash.message = message(code: 'folder.restored')
		redirect(controller: "message", action: "trash")
	}

//"${message(code: 'default.deleted.message', args: [message(code: 'group.label', default: 'Group'), ''])}"


	private def withFolder(Closure c) {
		def folderInstance = Folder.get(params.id)
		if (folderInstance) c folderInstance
		else render(text: message(code: 'folder.exist.not', args: [params.id])) // TODO handle error state properly
	}
}

