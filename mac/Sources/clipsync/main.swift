import ClipSyncCore
import Foundation

let usage = """
clipsync: ClipSync agent and CLI for the Alfred workflow

  clipsync start | stop          install+start / stop+remove the background agent (LaunchAgent)
  clipsync status [--alfred]     show status (or Alfred Script Filter JSON)
  clipsync send [--text TEXT]    send TEXT (default: current clipboard) to the phone
  clipsync pause | resume        pause / resume automatic sync of Mac copies
  clipsync pair [--no-show]      create a pairing and show its QR code
  clipsync pair-link             print the current pairing link (to paste instead of scanning)
  clipsync unpair [PID|--all]    forget a phone
  clipsync last                  copy the last clip received from the phone
  clipsync run ACTION            run an Alfred menu action; prints the notification text
  clipsync agent                 run the agent in the foreground (used by launchd)
  clipsync debug-client [...]    act as a fake phone for testing (see DebugClient.swift)
"""

signal(SIGPIPE, SIG_IGN)
var arguments = Array(CommandLine.arguments.dropFirst())
let command = arguments.isEmpty ? "help" : arguments.removeFirst()

func option(_ name: String) -> String? {
    guard let index = arguments.firstIndex(of: name), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}

if command != "agent" && command != "debug-client" {
    Commands.applyAlfredSettings()
}

do {
    let output: String
    switch command {
    case "agent": Agent.run()
    case "debug-client": DebugClient.run(arguments)
    case "start": output = try LaunchAgentManager.start()
    case "stop": output = try LaunchAgentManager.stop()
    case "status": output = arguments.contains("--alfred") ? Commands.alfredStatus() : Commands.statusText()
    case "send": output = try Commands.send(text: option("--text"))
    case "pause", "resume": output = try Commands.agentRequest(ControlRequest(cmd: command))
    case "pair": output = try { try Commands.ensureAgentRunning(); return try PairCommand.run(show: !arguments.contains("--no-show")) }()
    case "pair-link": output = try PairCommand.link()
    case "unpair": output = try Commands.unpair(arguments.first)
    case "last": output = try Commands.copyLast()
    case "run":
        guard let action = arguments.first else { throw CLIError("usage: clipsync run ACTION") }
        output = try Commands.runAction(action)
    case "help", "-h", "--help": output = usage
    default: throw CLIError("unknown command \(command)\n\n\(usage)")
    }
    if !output.isEmpty { print(output) }
} catch {
    // For Alfred, the error text becomes the notification; the exit code signals failure.
    print("⚠️ \(error)")
    exit(1)
}
