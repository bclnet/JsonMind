import XCTest
@testable import JsonMind
import JsonUICore

final class ActorCommandTests: XCTestCase {
    func testParsing() {
        XCTAssertEqual(ActorCommand(["play": "sing"]), .play(animation: "sing", loop: nil, speed: nil))
        XCTAssertEqual(ActorCommand(["play": ["animation": "sing", "loop": true, "speed": 2]]), .play(animation: "sing", loop: true, speed: 2))
        XCTAssertEqual(ActorCommand(["sound": "song", "loop": true]), .sound(name: "song", loop: true))
        XCTAssertEqual(ActorCommand(["stopSound": true]), .stopSound(name: nil))
        XCTAssertEqual(ActorCommand(["stopSound": "song"]), .stopSound(name: "song"))
        XCTAssertEqual(ActorCommand(["say": "hi"]), .say("hi"))
        XCTAssertEqual(ActorCommand(["moveTo": "user"]), .moveTo(.target(.user), stopAt: nil))
        XCTAssertEqual(ActorCommand(["moveTo": [1, 2, 3], "stopAt": 0.2]), .moveTo(.point(Point3(1, 2, 3)), stopAt: 0.2))
        XCTAssertEqual(ActorCommand(["moveTo": ["target": "bush", "stopAt": 0.4]]), .moveTo(.target(.actor("bush")), stopAt: 0.4))
        XCTAssertEqual(ActorCommand(["moveTo": ["x": 1, "z": 2]]), .moveTo(.point(Point3(1, 0, 2)), stopAt: nil))
        XCTAssertEqual(ActorCommand(["lookAt": "user"]), .lookAt(.user))
        XCTAssertEqual(ActorCommand(["stop": true]), .stop)
        XCTAssertEqual(ActorCommand(["wait": 1.5]), .wait(seconds: 1.5))
        XCTAssertEqual(ActorCommand(["emit": "sang"]), .emit(event: "sang"))
        XCTAssertEqual(ActorCommand(["behave": "wander"]), .behave("wander"))
        XCTAssertEqual(ActorCommand(["behave": ["type": "approach", "target": "user"]]), .behave(["type": "approach", "target": "user"]))
        XCTAssertNil(ActorCommand(["behave": 3]))
        XCTAssertNil(ActorCommand(["set": ["a": 1]]))
        XCTAssertNil(ActorCommand("play"))
        XCTAssertNil(ActorCommand(["say": 3]))
        XCTAssertNil(ActorCommand(["emit": ""]))
    }

    func testRoundTrip() {
        let commands: [ActorCommand] = [
            .play(animation: "a", loop: true, speed: 0.5), .sound(name: "s", loop: nil), .stopSound(name: nil), .say("x"),
            .moveTo(.point(Point3(1, 0, 1)), stopAt: 0.1), .moveTo(.target(.actor("b")), stopAt: nil), .lookAt(.user), .stop, .wait(seconds: 2), .emit(event: "e"), .behave("wander"),
        ]
        for c in commands { XCTAssertEqual(ActorCommand(c.value), c) }
    }

    func testScriptsMixCommandsAndActions() {
        let script = ActorScript([ ["play": "sing"], ["set": ["mood": "singing"]], "js: state.n = 1", "toast" ])!
        XCTAssertEqual(script.steps.count, 4)
        XCTAssertEqual(script.commands, [.play(animation: "sing", loop: nil, speed: nil)])
        guard case .action(.set) = script.steps[1], case .action(.script) = script.steps[2], case .action(.host(let name, _)) = script.steps[3] else { return XCTFail() }
        XCTAssertEqual(name, "toast")
        XCTAssertEqual(ActorScript(["say": "one"])?.steps.count, 1)
        XCTAssertNil(ActorScript(.null))
        XCTAssertEqual(ActorScript(script.value), script)
        let handlers = ActorScript.handlers(["tap": ["play": "x"], "bad": 5])
        XCTAssertEqual(handlers.keys.sorted(), ["tap"])
    }
}
